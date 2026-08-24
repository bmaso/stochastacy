package stochastacy.aws.examples.thermostatfleet

import java.nio.file.Path

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer

import stochastacy.aws.examples.demo.*

/**
 * Runnable Thermostat-fleet single-region demo: the `singleRegionDefault` scenario (a growing fleet of
 * thermostats writing telemetry to one on-demand table, queried and scanned via GSIs) as a Monte Carlo
 * ensemble, written as JSONL (per-GSI metrics included) plus a console summary. No external services.
 *
 * Flags (all optional): `--output <path>` `--seed <long>` `--trials <int>` `--ticks <long>`
 * `--parallelism <int>`; unset values fall back to `ThermostatConfig.singleRegionDefault`.
 */
@main def ThermostatFleetDemo(args: String*): Unit =
  def flag(name: String): Option[String] =
    args.grouped(2).collectFirst { case Seq(k, v) if k == s"--$name" => v }

  val output = flag("output").map(Path.of(_)).getOrElse(Path.of("/tmp/thermostat-fleet-single-region.jsonl"))
  val seed   = flag("seed").flatMap(_.toLongOption).getOrElse(1L)

  val base = ThermostatConfig.singleRegionDefault
  val config = base.copy(
    trialCount      = flag("trials").flatMap(_.toIntOption).getOrElse(base.trialCount),
    simulationTicks = flag("ticks").flatMap(_.toLongOption).getOrElse(base.simulationTicks),
    parallelism     = flag("parallelism").flatMap(_.toIntOption).getOrElse(base.parallelism)
  )

  given system: ActorSystem = ActorSystem("ThermostatFleetDemo")
  given Materializer        = Materializer.matFromSystem
  given ExecutionContext    = system.dispatcher
  try
    val result = Await.result(new SingleTableMonteCarloRunner().run(config, seed), 30.minutes)
    JsonlExport.write(output, result)

    def mean(metric: String): BigDecimal =
      result.aggregateSummary.collectFirst { case AggregateSummaryValue(`metric`, AggregateStatistic.Mean, v) => v }.getOrElse(BigDecimal(0))
    val gsiLines = config.globalSecondaryIndexes.map { g =>
      s"    ${g.indexName}: RCU=${mean(s"GSI:${g.indexName}:TotalReadCapacityUnits")}, WCU=${mean(s"GSI:${g.indexName}:TotalWriteCapacityUnits")}"
    }.mkString("\n")

    println(
      s"""Thermostat-fleet single-region — Monte Carlo summary (${result.trialCount} trials, ${config.simulationTicks} ticks)
         |  mean total read capacity units:  ${mean("TotalReadCapacityUnits")}
         |  mean total write capacity units: ${mean("TotalWriteCapacityUnits")}
         |  mean final storage bytes:        ${mean("FinalStorageBytes")}
         |  mean total estimated cost:       $$${mean("TotalEstimatedCost").setScale(8, BigDecimal.RoundingMode.HALF_UP)}
         |  per-GSI total capacity (mean):
         |$gsiLines
         |  wrote ${JsonlExport.records(result).size} records to $output""".stripMargin)
  finally
    Await.result(system.terminate(), 30.seconds)
