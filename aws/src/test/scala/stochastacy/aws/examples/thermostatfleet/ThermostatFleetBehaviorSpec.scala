package stochastacy.aws.examples.thermostatfleet

import org.apache.commons.rng.simple.RandomSource
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.aws.dynamodb.*
import stochastacy.aws.dynamodb.TableMechanics.OperationOutcome

class ThermostatFleetBehaviorSpec extends AnyWordSpec with should.Matchers:

  private val config   = ThermostatConfig.singleRegionDefault // fleetSize(0) = 3000
  private val behavior = new ThermostatFleetBehavior(config)
  private val strong   = ReadConsistency.StronglyConsistent

  private val N = 200000
  private val tol = 0.01

  private val populated = TableSummaryState.initial(itemCount = 5000L, averageItemBytes = 300L)
  private val readsRng  = RandomSource.KISS.create(3L)

  /** Fraction of `N` telemetry writes (against `state` at `tick`) that are inserts (previous = None). */
  private def insertFraction(state: TableSummaryState, tick: Long): Double =
    val rng = RandomSource.KISS.create(9L)
    val inserts = (0 until N).count { _ =>
      behavior.outcomeFor(PutItemRequest(300L), state, rng, tick) match
        case OperationOutcome.Put(_, None) => true
        case _                             => false
    }
    inserts.toDouble / N

  "ThermostatFleetBehavior — telemetry write (fleet saturation)" should {
    "insert every write on an empty table" in {
      insertFraction(TableSummaryState.empty, tick = 0L) shouldBe 1.0
    }

    "overwrite every write once the fleet is fully seen (itemCount >= fleetSize)" in {
      // fleetSize(0) = 3000; a table already holding 3000 items is saturated.
      insertFraction(TableSummaryState.initial(3000L, 300L), tick = 0L) shouldBe 0.0
    }

    "insert a partial fill at approximately (fleetSize - itemCount)/fleetSize" in {
      // 1500 of 3000 devices seen -> pNew = 0.5
      insertFraction(TableSummaryState.initial(1500L, 300L), tick = 0L) shouldBe (0.5 +- tol)
    }

    "insert more as the fleet grows with the tick (same item count, later tick)" in {
      val state = TableSummaryState.initial(1500L, 300L)
      // tick 0: fleet 3000, pNew = 0.5; tick 4800: fleet 3000 + 0.25*4800 = 4200, pNew = (4200-1500)/4200 ≈ 0.643
      insertFraction(state, tick = 4800L) should be > insertFraction(state, tick = 0L)
    }
  }

  "ThermostatFleetBehavior — reads" should {
    "make a customer-devices query evaluate 2..10 items, sized by the target's average" in {
      (0 until 2000).foreach { _ =>
        behavior.outcomeFor(QueryRequest(DynamoDbTarget.Gsi("customer-devices"), strong), populated, readsRng, 0L) match
          case OperationOutcome.Query(_, _, shape) =>
            shape.evaluatedItemCount should (be >= 2L and be <= 10L)
            shape.evaluatedBytes     shouldBe shape.evaluatedItemCount * 300L
            shape.returnedItemCount  should (be >= 1L and be <= shape.evaluatedItemCount)
          case other => fail(s"expected a Query, got $other")
      }
    }

    "make a fleet-alerts scan evaluate 50..250 items" in {
      (0 until 2000).foreach { _ =>
        behavior.outcomeFor(ScanRequest(DynamoDbTarget.Gsi("fleet-alerts"), strong), populated, readsRng, 0L) match
          case OperationOutcome.Scan(_, _, shape) =>
            shape.evaluatedItemCount should (be >= 50L and be <= 250L)
            shape.returnedItemCount  should be <= shape.evaluatedItemCount
          case other => fail(s"expected a Scan, got $other")
      }
    }

    "charge a read against the target's projected bytes (a KeysOnly GSI's smaller entries)" in {
      // A KeysOnly GSI over 5000 base items projects to 128 B/entry: the read is sized by that, not 300.
      val gsiState = TableSummaryState(itemCount = 5000L, totalItemBytes = 5000L * 128L)
      behavior.outcomeFor(QueryRequest(DynamoDbTarget.Gsi("customer-devices"), strong), gsiState, readsRng, 0L) match
        case OperationOutcome.Query(_, _, shape) => shape.evaluatedBytes shouldBe shape.evaluatedItemCount * 128L
        case other => fail(s"expected a Query, got $other")
    }

    "yield a zero shape when the target is empty" in {
      behavior.outcomeFor(ScanRequest(DynamoDbTarget.Gsi("fleet-alerts"), strong), TableSummaryState.empty, readsRng, 0L) match
        case OperationOutcome.Scan(_, _, shape) => shape shouldBe TableMechanics.ReadShape(0L, 0L, 0L, 0L)
        case other => fail(s"expected a Scan, got $other")
    }
  }

  "ThermostatConfig.singleRegionDefault" should {
    "declare the three GSIs (mixed projections) and the LSI, starting empty" in {
      config.globalSecondaryIndexes.map(_.indexName) shouldBe Vector("customer-devices", "fleet-alerts", "device-status")
      config.globalSecondaryIndexes.map(_.projection) shouldBe Vector(IndexProjection.KeysOnly, IndexProjection.Include(64L), IndexProjection.All)
      config.localSecondaryIndexes.map(_.indexName)  shouldBe Vector("reading-type-history")
      config.initialTableState                        shouldBe TableSummaryState.empty
      config.initialStorageBytesAllTargets            shouldBe 0L
    }
  }

  "ThermostatFleetBehavior, on a command dispatch," should {
    "resolve a TransactWriteItems into per-item inserts (status + audit), sized from the configured bytes" in {
      // variance off → the sub-item bytes are exactly the configured Vector; both are inserts (append-only)
      val b   = new ThermostatFleetBehavior(config.copy(telemetryItemBytesVariance = 0.0))
      val rng = RandomSource.KISS.create(1L)
      b.outcomeFor(TransactWriteItemsRequest(Vector(200L, 150L)), populated, rng, 0L) match
        case OperationOutcome.TransactWrite(items) =>
          items.map(i => (i.writtenItemBytes, i.previousItemBytes)) shouldBe Vector((200L, None), (150L, None))
        case other => fail(s"expected a TransactWrite outcome, got $other")
    }
  }
