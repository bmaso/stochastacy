package stochastacy.aws.dynamodb

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.simple.RandomSource
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.aws.dynamodb.TableMechanics.OperationOutcome
import stochastacy.core.component.Emission
import stochastacy.core.sampler.LogNormalSampler

/** Hot-partition throttling (Slice 1) + instant adaptive capacity (Slice 2): provisioned capacity is split
 *  across derived physical partitions, so load concentrated on one partition throttles below the table
 *  capacity. With adaptive capacity on (the DynamoDB default) that partition's ceiling is the physical max
 *  (1000 WCU here); with adaptive off it is the fair share (800). A well-distributed workload admits more,
 *  and a behavior with no partition access is byte-identical (table ceiling only). */
class HotPartitionSpec extends AnyWordSpec with should.Matchers:

  // Provisioned(read 1000, write 4000) → derive: ceil(1000/3000 + 4000/1000) = 5 partitions. Per-partition
  // fair-share write ceiling = 4000/5 = 800 WCU; physical-max write ceiling = 1000 WCU. Table ceiling = 4000.
  private val billing = BillingMode.Provisioned(readCapacityUnits = 1000, writeCapacityUnits = 4000)

  private def putBehavior(access: (DynamoDbRequest, UniformRandomProvider) => Option[String]) = new TableBehavior:
    def outcomeFor(request: DynamoDbRequest, state: TableSummaryState, rng: UniformRandomProvider, tick: Long): OperationOutcome =
      request match
        case PutItemRequest(bytes) => OperationOutcome.Put(writtenItemBytes = bytes, previousItemBytes = None)
        case other                 => throw new IllegalArgumentException(s"unexpected $other")
    override def partitionAccessFor(request: DynamoDbRequest, rng: UniformRandomProvider): Option[String] = access(request, rng)

  private val latency = LogNormalSampler.constant(math.log(0.01), 0.0)
  private val rng: UniformRandomProvider = RandomSource.KISS.create(1L)

  private def sampler(
    access:           (DynamoDbRequest, UniformRandomProvider) => Option[String],
    adaptiveCapacity: Boolean = true
  ): DynamoDbTable.DynamoDbTableSampler =
    new DynamoDbTable.DynamoDbTableSampler(DynamoDbTable.Config(
      initialState = TableSummaryState.empty, behavior = putBehavior(access), latency = latency,
      billingMode = billing, adaptiveCapacity = adaptiveCapacity
    ))

  /** Admit 1 KB (1 WCU) writes until one throttles; return how many were admitted. */
  private def admitsUntilThrottle(s: DynamoDbTable.DynamoDbTableSampler): Int =
    var st = s.initialState
    var n  = 0
    var done = false
    while !done do
      val e: Emission[TableState, DynamoDbResponse, DynamoDbConsumption] = s.sample(PutItemRequest(1024L), st, rng)
      e.output.event match
        case ThrottledResponse => done = true
        case _                 => st = e.newState; n += 1
    n

  "A provisioned table with hot-partition access" should {

    "relieve a concentrated key to the per-partition physical max under adaptive capacity (below the table)" in {
      // adaptive on (default): every write routes to one partition, throttling at the physical max 1000 —
      // instant adaptive lets it borrow idle table capacity up to the physical limit, still below the 4000 table.
      admitsUntilThrottle(sampler((_, _) => Some("hot"))) shouldBe 1000
    }

    "throttle a concentrated key at its fair share with adaptive capacity disabled (the baseline)" in {
      // adaptive off: the without-adaptive behavior — throttles at the fair share 800 (4000/5), the Slice-1 result.
      admitsUntilThrottle(sampler((_, _) => Some("hot"), adaptiveCapacity = false)) shouldBe 800
    }

    "admit far more of a well-distributed workload (load spread across partitions)" in {
      // a distinct key per request spreads across the 5 partitions → no single partition hits the physical max
      // quickly; the table ceiling (4000) binds instead, so far more than the concentrated 1000 is admitted.
      admitsUntilThrottle(sampler((_, r) => Some(r.nextLong().toString))) should be > 1000
    }

    "be byte-identical with no partition access — throttle only at the table ceiling" in {
      // the default behavior returns None → per-partition path skipped → throttles at the table's 4000
      admitsUntilThrottle(sampler((_, _) => None)) shouldBe 4000
    }
  }
