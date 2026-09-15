package stochastacy.aws.dynamodb

import stochastacy.sim.SimInstant

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.simple.RandomSource
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.aws.dynamodb.TableMechanics.OperationOutcome
import stochastacy.core.sampler.LogNormalSampler

/** Replication at the table (phase-11 Slice 2): a local admitted write **taps** one `ReplicationWrite`; an
 *  inbound replicated write applied via **onFeedback** bills **rWCU** (base + index), never WCU, and never
 *  re-taps. rWCU is ungated this slice (always applied). */
class ReplicationSpec extends AnyWordSpec with should.Matchers:

  private val behavior = new TableBehavior:
    def outcomeFor(request: DynamoDbRequest, state: TableSummaryState, rng: UniformRandomProvider, tick: Long): OperationOutcome =
      request match
        case PutItemRequest(bytes) => OperationOutcome.Put(writtenItemBytes = bytes, previousItemBytes = None)
        case GetItemRequest        => OperationOutcome.Get(itemBytes = Some(100L), consistency = ReadConsistency.EventuallyConsistent)
        case other                 => throw new IllegalArgumentException(s"unexpected $other")

  private val latency = LogNormalSampler.constant(math.log(0.01), 0.0)
  private val rng: UniformRandomProvider = RandomSource.KISS.create(1L)

  private def sampler(gsi: Boolean = false, billing: BillingMode = BillingMode.OnDemand): DynamoDbTable.DynamoDbTableSampler =
    var cfg = DynamoDbTable.Config(initialState = TableSummaryState.empty, behavior = behavior, latency = latency, billingMode = billing)
    if gsi then cfg = cfg.withGlobalSecondaryIndex(GlobalSecondaryIndex("g", IndexProjection.All))
    new DynamoDbTable.DynamoDbTableSampler(cfg)

  "A local admitted write" should {
    "tap exactly one ReplicationWrite carrying the write" in {
      val s = sampler()
      s.sample(PutItemRequest(1024L), SimInstant(0L, 0.0), s.initialState, rng).taps.map(_.event) shouldBe List(ReplicationWrite(OperationOutcome.Put(1024L, None)))
    }
    "tap nothing for a read" in {
      val s = sampler()
      s.sample(GetItemRequest, SimInstant(0L, 0.0), s.initialState, rng).taps shouldBe empty
    }
    "tap nothing for a throttled write" in {
      val s = sampler(billing = BillingMode.Provisioned(readCapacityUnits = 1, writeCapacityUnits = 1))
      val e = s.sample(PutItemRequest(10240L), SimInstant(0L, 0.0), s.initialState, rng) // 10 WCU ≫ ceiling 1 → throttled
      e.output.event shouldBe ThrottledResponse
      e.taps shouldBe empty
    }
  }

  "onFeedback (an inbound replicated write)" should {
    "bill rWCU (not WCU) for base and index, grow storage, and never re-tap" in {
      val s  = sampler(gsi = true)
      val te = s.onFeedback(ReplicationWrite(OperationOutcome.Put(1024L, None)), SimInstant(0L, 0.0), s.initialState, rng)
      val facts = te.consumption.map(_.event)
      facts.collect { case ReplicatedWriteCapacityConsumed(_, t) => t }.toSet shouldBe Set(DynamoDbTarget.Table, DynamoDbTarget.Gsi("g"))
      facts.collect { case _: WriteCapacityConsumed => () }                    shouldBe empty
      te.newState.base.totalItemBytes                                          shouldBe 1024L
    }
    "always admit even under a tiny provisioned ceiling (rWCU ungated this slice)" in {
      val s  = sampler(billing = BillingMode.Provisioned(readCapacityUnits = 1, writeCapacityUnits = 1))
      val te = s.onFeedback(ReplicationWrite(OperationOutcome.Put(10240L, None)), SimInstant(0L, 0.0), s.initialState, rng)
      te.consumption.map(_.event).collect { case _: ReplicatedWriteCapacityConsumed => () } should not be empty
    }
  }
