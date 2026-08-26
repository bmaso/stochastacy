package stochastacy.aws.examples.thermostatfleet

import java.nio.file.Path

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer

import stochastacy.aws.examples.demo.*

/**
 * Runnable Thermostat-fleet multi-table demo: the `twoTableDefault` scenario (a heavily-queried
 * `device-registry` table plus the single-region `device-telemetry` table) as a Monte Carlo ensemble,
 * written as JSONL with per-table (`Table:<name>:…`) metrics plus a per-table console summary. No external
 * services.
 *
 * Flags (all optional): `--output <path>` `--seed <long>` `--trials <int>` `--ticks <long>`
 * `--parallelism <int>`; unset values fall back to `ThermostatMultiTableConfig.twoTableDefault`.
 */
@main def ThermostatMultiTableDemo(args: String*): Unit =
  def flag(name: String): Option[String] =
    args.grouped(2).collectFirst { case Seq(k, v) if k == s"--$name" => v }

  val output = flag("output").map(Path.of(_)).getOrElse(Path.of("/tmp/thermostat-fleet-multi-table.jsonl"))
  val seed   = flag("seed").flatMap(_.toLongOption).getOrElse(1L)

  val base = ThermostatMultiTableConfig.twoTableDefault
  val config = base.withEnsemble(
    trials = flag("trials").flatMap(_.toIntOption).getOrElse(base.trialCount),
    ticks  = flag("ticks").flatMap(_.toLongOption).getOrElse(base.simulationTicks),
    par    = flag("parallelism").flatMap(_.toIntOption).getOrElse(base.parallelism)
  )

  given system: ActorSystem = ActorSystem("ThermostatMultiTableDemo")
  given Materializer        = Materializer.matFromSystem
  given ExecutionContext    = system.dispatcher
  try
    val report = Await.result(new MultiTableMonteCarloRunner().runToFile(config, seed, output), 30.minutes)

    def mean(table: TableAggregate, metric: String): BigDecimal =
      table.aggregateSummary.collectFirst { case AggregateSummaryValue(`metric`, AggregateStatistic.Mean, v) => v }.getOrElse(BigDecimal(0))
    val tableLines = report.perTable.map { t =>
      f"    ${t.tableName}: RCU=${mean(t, "TotalReadCapacityUnits")}, WCU=${mean(t, "TotalWriteCapacityUnits")}, " +
        f"storage=${mean(t, "FinalStorageBytes")}, cost=$$${mean(t, "TotalEstimatedCost").setScale(8, BigDecimal.RoundingMode.HALF_UP)}"
    }.mkString("\n")

    println(
      s"""Thermostat-fleet multi-table — Monte Carlo summary (${report.trialCount} trials, ${config.simulationTicks} ticks)
         |  per-table totals (mean):
         |$tableLines
         |  wrote ${report.recordsWritten} records to $output""".stripMargin)
  finally
    Await.result(system.terminate(), 30.seconds)
