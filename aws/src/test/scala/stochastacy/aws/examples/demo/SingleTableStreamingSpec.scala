package stochastacy.aws.examples.demo

import java.nio.file.{Files, Path}

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}
import scala.jdk.CollectionConverters.*

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.aws.examples.ordertracking.OrderTrackingConfig

/**
 * The streaming `runToFile` path: records are written to disk as trials complete, and the returned report
 * carries the same across-trial aggregates as the collecting `run` — without ever holding all trials.
 */
class SingleTableStreamingSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("SingleTableStreamingSpec")
  private given Materializer        = Materializer.matFromSystem
  private given ExecutionContext    = system.dispatcher
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  // Indexed config exercises the per-GSI record columns; kept tiny so the test is fast.
  private val config = OrderTrackingConfig.indexedDefault.copy(trialCount = 4, parallelism = 2)

  private def runner = new SingleTableMonteCarloRunner()

  private def linesOf(path: Path): Vector[String] = Files.readAllLines(path).asScala.toVector

  "SingleTableMonteCarloRunner.runToFile" should {

    "write every record to disk and report the count" in {
      val out    = Files.createTempFile("streaming-", ".jsonl")
      val report = Await.result(runner.runToFile(config, masterSeed = 1L, out), 60.seconds)
      val lines  = linesOf(out)

      report.recordsWritten shouldBe lines.size.toLong
      lines.count(_.contains("\"recordType\":\"trial-time-series\""))     should be > 0
      lines.count(_.contains("\"recordType\":\"trial-summary\""))         shouldBe (config.trialCount * MonteCarloAggregation.summaryMetrics(config.globalSecondaryIndexes.map(_.indexName)).size)
      lines.count(_.contains("\"recordType\":\"aggregate-time-series\"")) should be > 0
      lines.count(_.contains("\"recordType\":\"aggregate-summary\""))     should be > 0
      // trial records for every trial id 0..trialCount-1
      (0 until config.trialCount).foreach { id =>
        lines.exists(_.contains(s"\"trialId\":$id")) shouldBe true
      }
      Files.deleteIfExists(out)
    }

    "return the same across-trial aggregates as the collecting run" in {
      val out       = Files.createTempFile("streaming-", ".jsonl")
      val report    = Await.result(runner.runToFile(config, masterSeed = 7L, out), 60.seconds)
      val collected = Await.result(runner.run(config, masterSeed = 7L), 60.seconds)

      report.aggregateSummary    shouldBe collected.aggregateSummary
      report.aggregateTimeSeries shouldBe collected.aggregateTimeSeries
      Files.deleteIfExists(out)
    }

    "be deterministic — the same seed yields byte-identical output" in {
      val a = Files.createTempFile("streaming-a-", ".jsonl")
      val b = Files.createTempFile("streaming-b-", ".jsonl")
      Await.result(runner.runToFile(config, masterSeed = 3L, a), 60.seconds)
      Await.result(runner.runToFile(config, masterSeed = 3L, b), 60.seconds)
      linesOf(a) shouldBe linesOf(b)
      Files.deleteIfExists(a); Files.deleteIfExists(b)
    }
  }
