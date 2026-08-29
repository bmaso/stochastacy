package stochastacy.aws.examples.payments

import java.nio.file.Path

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer

import stochastacy.aws.examples.demo.*

/**
 * Runnable payments-ledger transactions demo: the `PaymentsLedgerConfig.default` scenario (money transfers
 * as atomic two-item `TransactWriteItems`, balance checks as `TransactGetItems`) as a Monte Carlo ensemble,
 * written as JSONL plus a console summary. To make the transactional premium concrete, it also runs the
 * **identical** workload in single-operation form (`useTransactions = false`) and prints the ratio — which
 * should land at ≈2×, the two-phase-commit cost. No external services.
 *
 * Flags (all optional): `--output <path>` `--seed <long>` `--trials <int>` `--ticks <long>`
 * `--parallelism <int>`; unset values fall back to `PaymentsLedgerConfig.default`.
 */
@main def PaymentsLedgerDemo(args: String*): Unit =
  def flag(name: String): Option[String] =
    args.grouped(2).collectFirst { case Seq(k, v) if k == s"--$name" => v }

  val output = flag("output").map(Path.of(_)).getOrElse(Path.of("/tmp/payments-ledger.jsonl"))
  val seed   = flag("seed").flatMap(_.toLongOption).getOrElse(1L)

  val base = PaymentsLedgerConfig.default
  val txn = base.copy(
    trialCount      = flag("trials").flatMap(_.toIntOption).getOrElse(base.trialCount),
    simulationTicks = flag("ticks").flatMap(_.toLongOption).getOrElse(base.simulationTicks),
    parallelism     = flag("parallelism").flatMap(_.toIntOption).getOrElse(base.parallelism)
  )
  val singles = txn.copy(scenarioId = "payments-ledger-singles", useTransactions = false)

  given system: ActorSystem = ActorSystem("PaymentsLedgerDemo")
  given Materializer        = Materializer.matFromSystem
  given ExecutionContext    = system.dispatcher
  try
    val runner        = new SingleTableMonteCarloRunner()
    val txnReport     = Await.result(runner.runToFile(txn, seed, output), 30.minutes)
    val singlesReport = Await.result(runner.run(singles, seed), 30.minutes)

    def mean(summary: Vector[AggregateSummaryValue], metric: String): BigDecimal =
      summary.collectFirst { case AggregateSummaryValue(`metric`, AggregateStatistic.Mean, v) => v }.getOrElse(BigDecimal(0))

    val txnSummary     = txnReport.aggregateSummary
    val singlesSummary = singlesReport.aggregateSummary
    val txnWcu     = mean(txnSummary, "TotalWriteCapacityUnits")
    val singlesWcu = mean(singlesSummary, "TotalWriteCapacityUnits")
    val txnRcu     = mean(txnSummary, "TotalReadCapacityUnits")
    val singlesRcu = mean(singlesSummary, "TotalReadCapacityUnits")
    def ratio(a: BigDecimal, b: BigDecimal): String =
      if b == 0 then "n/a" else (a / b).setScale(3, BigDecimal.RoundingMode.HALF_UP).toString

    println(
      s"""Payments-ledger transactions — Monte Carlo summary (${txnReport.trialCount} trials, ${txn.simulationTicks} ticks)
         |  transactional write capacity units: $txnWcu
         |  single-write   write capacity units: $singlesWcu
         |  write premium (txn / singles):       ${ratio(txnWcu, singlesWcu)}x
         |  transactional read capacity units:  $txnRcu
         |  single-read    read capacity units:  $singlesRcu
         |  read premium (txn / singles):        ${ratio(txnRcu, singlesRcu)}x
         |  mean final storage bytes:           ${mean(txnSummary, "FinalStorageBytes")}
         |  mean total estimated cost:          $$${mean(txnSummary, "TotalEstimatedCost").setScale(8, BigDecimal.RoundingMode.HALF_UP)}
         |  wrote ${txnReport.recordsWritten} transactional records to $output""".stripMargin)
  finally
    Await.result(system.terminate(), 30.seconds)
