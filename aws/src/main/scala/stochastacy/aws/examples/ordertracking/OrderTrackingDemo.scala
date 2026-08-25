package stochastacy.aws.examples.ordertracking

import java.nio.file.Path

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer

import stochastacy.aws.examples.demo.*

/**
 * Runnable Order-Tracking Phase-1 demo: run the Monte Carlo ensemble and write the results as JSONL,
 * plus a short console summary. No external services — the Postgres `stage` / Grafana `view` pipeline is
 * intentionally out of this (core-only) module.
 *
 * Flags (all optional): `--output <path>` `--seed <long>` `--trials <int>` `--ticks <long>`
 * `--parallelism <int>`; unset values fall back to `OrderTrackingConfig.phase1Default`.
 */
@main def OrderTrackingDemo(args: String*): Unit =
  val flags = parseFlags(args)
  val output = flags.get("output").map(Path.of(_)).getOrElse(Path.of("/tmp/order-tracking-phase1.jsonl"))
  val seed   = flags.get("seed").flatMap(_.toLongOption).getOrElse(1L)

  val base = OrderTrackingConfig.phase1Default
  val config = base.copy(
    trialCount      = flags.get("trials").flatMap(_.toIntOption).getOrElse(base.trialCount),
    simulationTicks = flags.get("ticks").flatMap(_.toLongOption).getOrElse(base.simulationTicks),
    parallelism     = flags.get("parallelism").flatMap(_.toIntOption).getOrElse(base.parallelism)
  )

  given system: ActorSystem     = ActorSystem("OrderTrackingDemo")
  given Materializer            = Materializer.matFromSystem
  given ExecutionContext        = system.dispatcher
  try
    val report = Await.result(new SingleTableMonteCarloRunner().runToFile(config, seed, output), 10.minutes)
    println(summaryText(config, output, report))
  finally
    Await.result(system.terminate(), 30.seconds)

private def parseFlags(args: Seq[String]): Map[String, String] =
  args.grouped(2).collect { case Seq(k, v) if k.startsWith("--") => k.drop(2) -> v }.toMap

private def summaryText(config: OrderTrackingConfig, output: Path, report: MonteCarloRunReport): String =
  def mean(metric: String): BigDecimal =
    report.aggregateSummary
      .collectFirst { case AggregateSummaryValue(`metric`, AggregateStatistic.Mean, v) => v }
      .getOrElse(BigDecimal(0))

  s"""Order-Tracking Phase-1 — Monte Carlo summary (${report.trialCount} trials, ${config.simulationTicks} ticks)
     |  mean total read capacity units:  ${mean("TotalReadCapacityUnits")}
     |  mean total write capacity units: ${mean("TotalWriteCapacityUnits")}
     |  mean final storage bytes:        ${mean("FinalStorageBytes")}
     |  mean total estimated cost:       $$${mean("TotalEstimatedCost").setScale(8, BigDecimal.RoundingMode.HALF_UP)}
     |  wrote ${report.recordsWritten} records to $output""".stripMargin
