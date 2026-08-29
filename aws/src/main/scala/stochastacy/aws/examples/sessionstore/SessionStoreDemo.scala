package stochastacy.aws.examples.sessionstore

import java.nio.file.Path

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer

import stochastacy.aws.examples.demo.*

/**
 * Runnable session-store TTL demo: the `SessionStoreConfig.default` scenario (a login service writing one
 * session per sign-in to a single on-demand table with a KeysOnly `user-sessions` GSI, each session
 * expiring `ttlPeriodTicks` after it is written) as a Monte Carlo ensemble, written as JSONL plus a console
 * summary. Its point is the **storage plateau**: mean stored bytes climb for `ttlPeriodTicks` ticks, then
 * flatten as creations and expiries balance. No external services.
 *
 * Flags (all optional): `--output <path>` `--seed <long>` `--trials <int>` `--ticks <long>`
 * `--parallelism <int>`; unset values fall back to `SessionStoreConfig.default`.
 */
@main def SessionStoreDemo(args: String*): Unit =
  def flag(name: String): Option[String] =
    args.grouped(2).collectFirst { case Seq(k, v) if k == s"--$name" => v }

  val output = flag("output").map(Path.of(_)).getOrElse(Path.of("/tmp/session-store-ttl.jsonl"))
  val seed   = flag("seed").flatMap(_.toLongOption).getOrElse(1L)

  val base = SessionStoreConfig.default
  val config = base.copy(
    trialCount      = flag("trials").flatMap(_.toIntOption).getOrElse(base.trialCount),
    simulationTicks = flag("ticks").flatMap(_.toLongOption).getOrElse(base.simulationTicks),
    parallelism     = flag("parallelism").flatMap(_.toIntOption).getOrElse(base.parallelism)
  )

  given system: ActorSystem = ActorSystem("SessionStoreDemo")
  given Materializer        = Materializer.matFromSystem
  given ExecutionContext    = system.dispatcher
  try
    val report = Await.result(new SingleTableMonteCarloRunner().runToFile(config, seed, output), 30.minutes)

    def mean(metric: String): BigDecimal =
      report.aggregateSummary.collectFirst { case AggregateSummaryValue(`metric`, AggregateStatistic.Mean, v) => v }.getOrElse(BigDecimal(0))

    def storageAt(tick: Long): BigDecimal =
      report.aggregateTimeSeries.collectFirst {
        case AggregateTimeSeriesPoint(`tick`, "StorageBytes", AggregateStatistic.Mean, v) => v
      }.getOrElse(BigDecimal(0))

    // Sample the mean storage curve to show the plateau: mid-climb, at the TTL horizon, and at the end.
    val ttl       = config.ttlPeriodTicks.getOrElse(0)
    val plateau   = config.ttlPeriodTicks match
      case Some(p) =>
        s"""  storage plateau (mean stored bytes):
           |    tick ${p / 2}: ${storageAt(p / 2L)}
           |    tick $p (TTL horizon): ${storageAt(p.toLong)}
           |    tick ${config.simulationTicks} (end): ${storageAt(config.simulationTicks)}""".stripMargin
      case None => "  (TTL off — storage rises unbounded)"

    println(
      s"""Session-store TTL — Monte Carlo summary (${report.trialCount} trials, ${config.simulationTicks} ticks, TTL=$ttl)
         |  mean total read capacity units:  ${mean("TotalReadCapacityUnits")}
         |  mean total write capacity units: ${mean("TotalWriteCapacityUnits")}
         |  mean final storage bytes:        ${mean("FinalStorageBytes")}
         |  mean total estimated cost:       $$${mean("TotalEstimatedCost").setScale(8, BigDecimal.RoundingMode.HALF_UP)}
         |$plateau
         |  wrote ${report.recordsWritten} records to $output""".stripMargin)
  finally
    Await.result(system.terminate(), 30.seconds)
