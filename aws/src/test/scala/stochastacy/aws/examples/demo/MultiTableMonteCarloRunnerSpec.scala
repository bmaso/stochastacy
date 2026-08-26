package stochastacy.aws.examples.demo

import java.nio.file.Files

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}
import scala.jdk.CollectionConverters.*

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.aws.examples.ordertracking.OrderTrackingConfig

class MultiTableMonteCarloRunnerSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("MultiTableMonteCarloRunnerSpec")
  private given Materializer        = Materializer.matFromSystem
  private given ExecutionContext    = system.dispatcher
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  // table-a is index-free (so its base per-table aggregates equal a full single-table ensemble); table-b has GSIs.
  private val cfgA  = OrderTrackingConfig.phase1Default.copy(trialCount = 4)
  private val specA = cfgA.tableSpec.copy(tableName = "device-registry")
  private val specB = OrderTrackingConfig.indexedDefault.copy(trialCount = 4).tableSpec.copy(tableName = "device-telemetry")

  private val scenario: MultiTableScenario = new MultiTableScenario:
    def scenarioId      = "test-multi"
    def simulationTicks = 30L
    def trialCount      = 4
    def parallelism     = 2
    def tables          = Vector(specA, specB)

  private def runner = new MultiTableMonteCarloRunner()

  "MultiTableMonteCarloRunner" should {

    "aggregate each table exactly as a standalone single-table ensemble (index-free table)" in {
      val multiRes = Await.result(runner.run(scenario, masterSeed = 1L), 90.seconds)
      val single   = Await.result(new SingleTableMonteCarloRunner().run(cfgA, masterSeed = 1L), 90.seconds)
      // table-a's per-trial seeds match the single-table runner (derive prefix property), and both are
      // base-metric-only (cfgA has no GSIs), so the full aggregate vectors are identical.
      multiRes.perTable.find(_.tableName == "device-registry").map(_.aggregateSummary) shouldBe Some(single.aggregateSummary)
    }

    "write Table:<name>: records for every table — base metrics only, no per-GSI or overall" in {
      val out    = Files.createTempFile("mt-", ".jsonl")
      val report = Await.result(runner.runToFile(scenario, masterSeed = 2L, out), 90.seconds)
      val lines  = Files.readAllLines(out).asScala.toVector

      report.recordsWritten shouldBe lines.size.toLong
      lines.exists(_.contains("\"metric\":\"Table:device-registry:TotalWriteCapacityUnits\""))  shouldBe true
      lines.exists(_.contains("\"metric\":\"Table:device-telemetry:TotalWriteCapacityUnits\"")) shouldBe true
      lines.count(_.contains("\"recordType\":\"aggregate-summary\"")) should be > 0
      // base metrics only: no per-GSI-within-table, and no un-prefixed / overall metric records
      lines.exists(_.contains("Table:device-telemetry:GSI:"))         shouldBe false
      lines.exists(_.contains("\"metric\":\"TotalWriteCapacityUnits\"")) shouldBe false
      Files.deleteIfExists(out)
    }

    "return the same per-table aggregates as the collecting run" in {
      val out       = Files.createTempFile("mt-", ".jsonl")
      val report    = Await.result(runner.runToFile(scenario, masterSeed = 7L, out), 90.seconds)
      val collected = Await.result(runner.run(scenario, masterSeed = 7L), 90.seconds)
      report.perTable shouldBe collected.perTable
      Files.deleteIfExists(out)
    }

    "be deterministic — the same seed yields byte-identical output" in {
      val a = Files.createTempFile("mt-a-", ".jsonl")
      val b = Files.createTempFile("mt-b-", ".jsonl")
      Await.result(runner.runToFile(scenario, masterSeed = 3L, a), 90.seconds)
      Await.result(runner.runToFile(scenario, masterSeed = 3L, b), 90.seconds)
      Files.readAllLines(a).asScala.toVector shouldBe Files.readAllLines(b).asScala.toVector
      Files.deleteIfExists(a); Files.deleteIfExists(b)
    }
  }
