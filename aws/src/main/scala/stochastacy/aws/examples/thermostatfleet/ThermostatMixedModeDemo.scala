package stochastacy.aws.examples.thermostatfleet

import java.nio.file.Path

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer

import stochastacy.aws.examples.demo.*

/**
 * Runnable Thermostat-fleet mixed-mode demo: the single-region telemetry workload that **starts on-demand,
 * switches to provisioned at tick 400, then right-sizes down at tick 800** (`ThermostatConfig.mixedModeDefault`)
 * as a Monte Carlo ensemble, written as JSONL plus a console summary showing the estimated cost, the
 * provisioned-capacity reservation, and the throttle count that the tightened capacity produces. No external
 * services.
 *
 * Flags (all optional): `--output <path>` `--seed <long>` `--trials <int>` `--parallelism <int>`; the tick
 * horizon is fixed by the reconfiguration schedule (400 / 800 within 1200 ticks).
 */
@main def ThermostatMixedModeDemo(args: String*): Unit =
  def flag(name: String): Option[String] =
    args.grouped(2).collectFirst { case Seq(k, v) if k == s"--$name" => v }

  val output = flag("output").map(Path.of(_)).getOrElse(Path.of("/tmp/thermostat-fleet-mixed-mode.jsonl"))
  val seed   = flag("seed").flatMap(_.toLongOption).getOrElse(1L)

  val base = ThermostatConfig.mixedModeDefault
  val config = base.copy(
    trialCount  = flag("trials").flatMap(_.toIntOption).getOrElse(base.trialCount),
    parallelism = flag("parallelism").flatMap(_.toIntOption).getOrElse(base.parallelism)
  )

  given system: ActorSystem = ActorSystem("ThermostatMixedModeDemo")
  given Materializer        = Materializer.matFromSystem
  given ExecutionContext    = system.dispatcher
  try
    val report = Await.result(new SingleTableMonteCarloRunner().runToFile(config, seed, output), 30.minutes)

    def mean(metric: String): BigDecimal =
      report.aggregateSummary.collectFirst { case AggregateSummaryValue(`metric`, AggregateStatistic.Mean, v) => v }.getOrElse(BigDecimal(0))

    println(
      s"""Thermostat-fleet mixed-mode — Monte Carlo summary (${report.trialCount} trials, ${config.simulationTicks} ticks)
         |  on-demand -> Provisioned(250,125)@400 -> Provisioned(100,333)@800
         |  mean total read capacity units (consumed):  ${mean("TotalReadCapacityUnits")}
         |  mean total write capacity units (consumed): ${mean("TotalWriteCapacityUnits")}
         |  mean provisioned read capacity-unit-ticks:  ${mean("TotalProvisionedReadCapacityUnitTicks")}
         |  mean provisioned write capacity-unit-ticks: ${mean("TotalProvisionedWriteCapacityUnitTicks")}
         |  mean throttled requests:                    ${mean("TotalThrottledRequests")}
         |  mean final storage bytes:                   ${mean("FinalStorageBytes")}
         |  mean total estimated cost:                  $$${mean("TotalEstimatedCost").setScale(8, BigDecimal.RoundingMode.HALF_UP)}
         |  wrote ${report.recordsWritten} records to $output""".stripMargin)
  finally
    Await.result(system.terminate(), 30.seconds)
