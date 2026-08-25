package stochastacy.aws.examples.demo

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.aws.examples.ordertracking.OrderTrackingConfig

class MultiTableTrialRunnerSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("MultiTableTrialRunnerSpec")
  private given Materializer        = Materializer.matFromSystem
  private given ExecutionContext    = system.dispatcher
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  // Two distinct tables sharing the 30-tick horizon: a plain order-tracking table and the indexed one.
  private val specA = OrderTrackingConfig.phase1Default.tableSpec.copy(tableName = "table-a")
  private val specB = OrderTrackingConfig.indexedDefault.tableSpec.copy(tableName = "table-b")

  private val ticks = OrderTrackingConfig.phase1Default.simulationTicks // 30, matches both tables' arrivals

  private def multi(specs: TableSpec*): MultiTableScenario = new MultiTableScenario:
    def scenarioId      = "test-multi"
    def simulationTicks = ticks
    def trialCount      = 1
    def parallelism     = 1
    def tables          = specs.toVector

  private def runMulti(scenario: MultiTableScenario, seed: Long): MultiTableTrialResult =
    Await.result(new MultiTableTrialRunner().runTrial(scenario, trialId = 0, seed = seed), 60.seconds)

  "MultiTableTrialRunner.runTrial" should {

    "match the single-table runner for a one-table scenario (same seed → same leg)" in {
      val single = Await.result(new SingleTableTrialRunner().runTrial(OrderTrackingConfig.phase1Default, trialId = 0, seed = 1L), 60.seconds)
      val result = runMulti(multi(specA), seed = 1L)
      result.perTable.map(_._1) shouldBe Vector("table-a")
      result.perTable.head._2   shouldBe single // identical series + summary (only the table name differs, unused here)
    }

    "keep each table's result independent of the other tables present" in {
      val alone  = runMulti(multi(specA),        seed = 5L)
      val paired = runMulti(multi(specA, specB), seed = 5L)
      // table-a uses derive(seed, 3)[0..2] whether alone or paired (the derive prefix property)
      paired.perTable.find(_._1 == "table-a").map(_._2) shouldBe Some(alone.perTable.head._2)
    }

    "produce a non-empty per-table result for every table, in table order" in {
      val result = runMulti(multi(specA, specB), seed = 9L)
      result.perTable.map(_._1) shouldBe Vector("table-a", "table-b")
      all(result.perTable.map(_._2.summary.totalWriteCapacityUnits)) should be > BigDecimal(0)
      all(result.perTable.map(_._2.timeSeries.size))                 shouldBe ticks.toInt
    }

    "be deterministic under a fixed seed" in {
      runMulti(multi(specA, specB), seed = 7L) shouldBe runMulti(multi(specA, specB), seed = 7L)
    }
  }
