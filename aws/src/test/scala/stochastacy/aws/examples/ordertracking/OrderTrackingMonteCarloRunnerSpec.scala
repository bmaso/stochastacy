package stochastacy.aws.examples.ordertracking

import stochastacy.aws.examples.demo.*

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.json4s.*
import org.json4s.jackson.JsonMethods
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class OrderTrackingMonteCarloRunnerSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem  = ActorSystem("OrderTrackingMonteCarloRunnerSpec")
  private given Materializer         = Materializer.matFromSystem
  private given ExecutionContext     = system.dispatcher
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  private val trials = 6
  private val ticks  = 5L
  private val config = OrderTrackingConfig.phase1Default.copy(trialCount = trials, simulationTicks = ticks, parallelism = 4)

  private def run(cfg: OrderTrackingConfig, seed: Long): MonteCarloResult =
    Await.result(new SingleTableMonteCarloRunner().run(cfg, seed), 60.seconds)

  "SingleTableMonteCarloRunner" should {

    "be reproducible under a fixed master seed" in {
      run(config, seed = 42L) shouldBe run(config, seed = 42L)
    }

    "produce identical results regardless of parallelism" in {
      val p1 = run(config.copy(parallelism = 1), seed = 7L)
      val p4 = run(config.copy(parallelism = 4), seed = 7L)
      p1.trials              shouldBe p4.trials
      p1.aggregateSummary    shouldBe p4.aggregateSummary
      p1.aggregateTimeSeries shouldBe p4.aggregateTimeSeries
    }

    "assign trial ids 0..n-1 and aggregate over the whole ensemble" in {
      val result = run(config, seed = 1L)
      result.trials.map(_.trialId) shouldBe (0 until trials).toVector
      result.aggregateSummary.count(_.statistic == AggregateStatistic.Mean) shouldBe MonteCarloAggregation.summaryMetrics(Vector.empty).size
    }

    "emit JSONL records in the expected counts" in {
      val records = JsonlExport.records(run(config, seed = 1L))
      def count(rt: String): Int = records.count {
        case r: DemoRecord.TrialTimeSeries    => rt == "trial-time-series"
        case r: DemoRecord.TrialSummary       => rt == "trial-summary"
        case r: DemoRecord.AggregateTimeSeries => rt == "aggregate-time-series"
        case r: DemoRecord.AggregateSummary   => rt == "aggregate-summary"
      }
      count("trial-time-series")     shouldBe trials * ticks.toInt * 4
      count("trial-summary")         shouldBe trials * 5
      count("aggregate-time-series") shouldBe ticks.toInt * 4 * 2
      count("aggregate-summary")     shouldBe 5 * 2
    }

    "emit per-GSI aggregate records for the indexed scenario" in {
      val indexed = OrderTrackingConfig.indexedDefault.copy(trialCount = 4, simulationTicks = 5, parallelism = 4)
      val records = JsonlExport.records(run(indexed, seed = 1L))
      def hasAggregateSummary(metric: String): Boolean =
        records.exists { case r: DemoRecord.AggregateSummary => r.metric == metric; case _ => false }
      hasAggregateSummary("GSI:customerId-status:TotalReadCapacityUnits")   shouldBe true
      hasAggregateSummary("GSI:customerId-status:TotalWriteCapacityUnits")  shouldBe true
      hasAggregateSummary("GSI:sellerId-createdAt:TotalWriteCapacityUnits") shouldBe true
    }

    "render well-formed JSONL, one JSON object per line" in {
      val rendered = JsonlExport.render(run(config, seed = 1L))
      val lines    = rendered.linesIterator.toVector
      lines should not be empty
      all(lines.map(l => JsonMethods.parse(l) \ "recordType")) should not be org.json4s.JNothing
    }
  }
