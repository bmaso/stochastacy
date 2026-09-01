package stochastacy.aws.dynamodb

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.simple.RandomSource
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.aws.dynamodb.TableMechanics.OperationOutcome
import stochastacy.core.component.Emission
import stochastacy.core.sampler.LogNormalSampler

/** Hot-partition throttling (Slice 1): provisioned capacity is split across derived physical partitions, so
 *  load concentrated on one partition throttles at its fair-share ceiling while the table has aggregate spare;
 *  a well-distributed workload does not, and a behavior with no partition access is byte-identical. */
class HotPartitionSpec extends AnyWordSpec with should.Matchers:

  // Provisioned(read 1000, write 4000) → derive: ceil(1000/3000 + 4000/1000) = 5 partitions; per-partition
  // write ceiling = 4000/5 = 800 WCU. Table ceiling = 4000 WCU.
  private val billing = BillingMode.Provisioned(readCapacityUnits = 1000, writeCapacityUnits = 4000)

  private def putBehavior(access: (DynamoDbRequest, UniformRandomProvider) => Option[String]) = new TableBehavior:
    def outcomeFor(request: DynamoDbRequest, state: TableSummaryState, rng: UniformRandomProvider, tick: Long): OperationOutcome =
      request match
        case PutItemRequest(bytes) => OperationOutcome.Put(writtenItemBytes = bytes, previousItemBytes = None)
        case other                 => throw new IllegalArgumentException(s"unexpected $other")
    override def partitionAccessFor(request: DynamoDbRequest, rng: UniformRandomProvider): Option[String] = access(request, rng)

  private val latency = LogNormalSampler.constant(math.log(0.01), 0.0)
  private val rng: UniformRandomProvider = RandomSource.KISS.create(1L)

  private def sampler(access: (DynamoDbRequest, UniformRandomProvider) => Option[String]): DynamoDbTable.DynamoDbTableSampler =
    new DynamoDbTable.DynamoDbTableSampler(DynamoDbTable.Config(
      initialState = TableSummaryState.empty, behavior = putBehavior(access), latency = latency, billingMode = billing
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

    "throttle a concentrated key at its partition's fair-share ceiling, below the table capacity" in {
      // every write routes to one partition → throttles at 800 (4000/5), not the table's 4000
      admitsUntilThrottle(sampler((_, _) => Some("hot"))) shouldBe 800
    }

    "admit far more of a well-distributed workload (load spread across partitions)" in {
      // a distinct key per request spreads across the 5 partitions → no single partition hits 800 quickly
      admitsUntilThrottle(sampler((_, r) => Some(r.nextLong().toString))) should be > 800
    }

    "be byte-identical with no partition access — throttle only at the table ceiling" in {
      // the default behavior returns None → per-partition path skipped → throttles at the table's 4000
      admitsUntilThrottle(sampler((_, _) => None)) shouldBe 4000
    }
  }
