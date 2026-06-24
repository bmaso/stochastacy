package stochastacy.examples.eas

import org.apache.commons.rng.simple.RandomSource
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.{DynamoDBRequest, QueryRequest, PutItemRequest, GetItemRequest}
import stochastacy.sim.{TimedControlEvent, ticks}
import stochastacy.workload.WorkloadRequestStream

/**
 * Suite 1 — pure iterator, no Pekko.
 *
 * Verifies that WorkloadRequestStream from EasScenarioConfig.toAlertsWorkload and
 * toUasWorkload terminates and produces the expected tick structure.  No ActorSystem
 * or Materializer is needed; we just call .toVector on the iterator.
 *
 * If this suite fails or hangs the problem is in WorkloadRequestStream or the workload
 * builder methods — not in the feedback loop.
 */
class WorkloadRequestStreamTerminatesSpec extends AnyWordSpec with Matchers:

  private val SimTicks = 10L
  private val config   = EasScenarioConfig(simulationTicks = SimTicks, burstMultiplier = 2.0)

  "WorkloadRequestStream from toAlertsWorkload" should {

    "terminate and produce exactly simulationTicks+1 Tick elements" in {
      val rng      = RandomSource.KISS.create(42L)
      val elements = WorkloadRequestStream(config.toAlertsWorkload, rng, SimTicks).toVector
      val ticks    = elements.collect { case t: TimedControlEvent.Tick => t }
      // WorkloadRequestStream emits Tick(1)…Tick(N) plus one final drain Tick(N+1)
      ticks should have size (SimTicks + 1)
    }

    "produce DynamoDBRequest elements in addition to Tick events" in {
      val rng      = RandomSource.KISS.create(42L)
      val elements = WorkloadRequestStream(config.toAlertsWorkload, rng, SimTicks).toVector
      val requests = elements.collect { case r: DynamoDBRequest => r }
      requests should not be empty
    }

    "emit only independent-flow ids (a1-poll, a3-write) — no derived ids" in {
      // WorkloadRequestStream only runs the Independent flows.  Retry and FollowOn
      // ids (a1-retry, a2-fetch) must NOT appear here; they come from FollowOnTransformerStage.
      val rng      = RandomSource.KISS.create(42L)
      val elements = WorkloadRequestStream(config.toAlertsWorkload, rng, SimTicks).toVector
      val flowIds  = elements.collect { case r: DynamoDBRequest => r }.flatMap(_.flowId).toSet
      flowIds should not contain "a1-retry"
      flowIds should not contain "a2-fetch"
      flowIds.foreach { fid =>
        fid should (equal("a1-poll") or equal("a3-write"))
      }
    }

    "emit QueryRequests for a1-poll and PutItemRequests for a3-write" in {
      val rng      = RandomSource.KISS.create(42L)
      val elements = WorkloadRequestStream(config.toAlertsWorkload, rng, SimTicks).toVector
      val queries  = elements.collect { case r: QueryRequest if r.flowId.contains("a1-poll") => r }
      val puts     = elements.collect { case r: PutItemRequest if r.flowId.contains("a3-write") => r }
      queries should not be empty
      // a3-write fires at lambda=0.2 in ticks [295,305]; with only 10 ticks it may produce 0 items
      // — just verify the type is right if any appear
      puts.foreach(_.itemBytes shouldBe 4500L)
    }

    "emit ticks in strictly ascending order" in {
      val rng       = RandomSource.KISS.create(42L)
      val elements  = WorkloadRequestStream(config.toAlertsWorkload, rng, SimTicks).toVector
      val tickTimes: Vector[Long] =
        elements.collect { case t: TimedControlEvent.Tick => t.eventTime.ticks }
      // Each consecutive tick must be exactly 1 greater than the previous
      tickTimes.zip(tickTimes.tail).foreach { case (a, b) => b shouldBe (a + 1L) }
    }
  }

  "WorkloadRequestStream from toUasWorkload" should {

    "terminate and produce exactly simulationTicks+1 Tick elements" in {
      val rng      = RandomSource.KISS.create(77L)
      val elements = WorkloadRequestStream(config.toUasWorkload, rng, SimTicks).toVector
      val ticks    = elements.collect { case t: TimedControlEvent.Tick => t }
      ticks should have size (SimTicks + 1)
    }

    "emit only s1-delivered, s2-opened, s3-acknowledged flow ids" in {
      val rng      = RandomSource.KISS.create(77L)
      val elements = WorkloadRequestStream(config.toUasWorkload, rng, 900L).toVector
      val flowIds  = elements.collect { case r: DynamoDBRequest => r }.flatMap(_.flowId).toSet
      flowIds.foreach { fid =>
        fid should (equal("s1-delivered") or equal("s2-opened") or equal("s3-acknowledged"))
      }
    }
  }
