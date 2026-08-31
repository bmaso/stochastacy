package stochastacy.aws.examples.thermostatfleet

import java.nio.file.Path

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer

import stochastacy.aws.examples.demo.*

/**
 * Runnable Thermostat-fleet **auto-scaling** demo: the single-region telemetry workload on a provisioned
 * table with **burst capacity + reactive auto-scaling** (`ThermostatConfig.autoScalingDefault`) as a Monte
 * Carlo ensemble, written as JSONL plus a console summary. To make the benefit concrete it also runs a
 * **fixed-provisioned** table at the same initial reservation (burst + auto-scaling off) on the identical
 * workload, and prints the throttle reduction. No external services.
 *
 * Flags (all optional): `--output <path>` `--seed <long>` `--trials <int>` `--parallelism <int>`.
 */
@main def ThermostatAutoScalingDemo(args: String*): Unit =
  def flag(name: String): Option[String] =
    args.grouped(2).collectFirst { case Seq(k, v) if k == s"--$name" => v }

  val output = flag("output").map(Path.of(_)).getOrElse(Path.of("/tmp/thermostat-fleet-autoscaling.jsonl"))
  val seed   = flag("seed").flatMap(_.toLongOption).getOrElse(1L)

  val base = ThermostatConfig.autoScalingDefault
  val autoScaled = base.copy(
    trialCount  = flag("trials").flatMap(_.toIntOption).getOrElse(base.trialCount),
    parallelism = flag("parallelism").flatMap(_.toIntOption).getOrElse(base.parallelism)
  )
  // The baseline: the same modest reservation held fixed (no burst, no auto-scaling).
  val fixed = autoScaled.copy(scenarioId = "thermostat-fleet-fixed", autoScalingPolicy = None, burstWindowTicks = 0)

  given system: ActorSystem = ActorSystem("ThermostatAutoScalingDemo")
  given Materializer        = Materializer.matFromSystem
  given ExecutionContext    = system.dispatcher
  try
    val runner        = new SingleTableMonteCarloRunner()
    val autoReport    = Await.result(runner.runToFile(autoScaled, seed, output), 30.minutes)
    val fixedReport   = Await.result(runner.run(fixed, seed), 30.minutes)

    def mean(summary: Vector[AggregateSummaryValue], metric: String): BigDecimal =
      summary.collectFirst { case AggregateSummaryValue(`metric`, AggregateStatistic.Mean, v) => v }.getOrElse(BigDecimal(0))

    val autoS  = autoReport.aggregateSummary
    val fixedS = fixedReport.aggregateSummary

    println(
      s"""Thermostat-fleet auto-scaling — Monte Carlo summary (${autoReport.trialCount} trials, ${autoScaled.simulationTicks} ticks)
         |  initial reservation: Provisioned(read=100, write=150); policy target 70%, write [50, 5000]; burst 300 ticks
         |  burst + auto-scaling:
         |    mean throttled requests:                 ${mean(autoS, "TotalThrottledRequests")}
         |    mean provisioned write capacity-ticks:   ${mean(autoS, "TotalProvisionedWriteCapacityUnitTicks")}
         |    mean total estimated cost:               $$${mean(autoS, "TotalEstimatedCost").setScale(8, BigDecimal.RoundingMode.HALF_UP)}
         |  fixed reservation (baseline):
         |    mean throttled requests:                 ${mean(fixedS, "TotalThrottledRequests")}
         |    mean provisioned write capacity-ticks:   ${mean(fixedS, "TotalProvisionedWriteCapacityUnitTicks")}
         |    mean total estimated cost:               $$${mean(fixedS, "TotalEstimatedCost").setScale(8, BigDecimal.RoundingMode.HALF_UP)}
         |  throttle reduction (fixed − auto):         ${mean(fixedS, "TotalThrottledRequests") - mean(autoS, "TotalThrottledRequests")}
         |  wrote ${autoReport.recordsWritten} records to $output""".stripMargin)
  finally
    Await.result(system.terminate(), 30.seconds)
