package stochastacy.aws.examples.thermostatfleet

import org.apache.commons.rng.UniformRandomProvider

import stochastacy.aws.dynamodb.*
import stochastacy.aws.dynamodb.TableMechanics.{OperationOutcome, ReadShape}

/**
 * The Thermostat-fleet domain behavior on the v2 [[TableBehavior]] interface — a faithful port of the
 * legacy `ThermostatFleetBehavior` (single-region), minus the partition-footprint / item-collection
 * bookkeeping this scope omits.
 *
 *   - a **telemetry write** either creates a new device record or overwrites an existing one, chosen by
 *     **fleet saturation**: the chance a write lands on a not-yet-seen device is ≈ `(fleetSize − itemCount)
 *     / fleetSize`, and the fleet grows with the tick — so early ticks are mostly inserts and the table
 *     fills toward the fleet size;
 *   - a **query** (customer-support, on `customer-devices`) evaluates 2–10 items of the target;
 *   - a **scan** (fleet-dashboard, on `fleet-alerts`) evaluates 50–250 items of the target.
 *
 * Read bytes come from the **target's own** (projected) average, routed in by the table — so a KeysOnly or
 * Include GSI read is charged for its smaller entries.
 */
final class ThermostatFleetBehavior(config: ThermostatConfig) extends TableBehavior:

  def outcomeFor(request: DynamoDbRequest, state: TableSummaryState, rng: UniformRandomProvider, tick: Long): OperationOutcome =
    request match
      case PutItemRequest(itemBytes) =>
        OperationOutcome.Put(writtenItemBytes = itemBytes, previousItemBytes = telemetryPrevious(state, rng, tick))
      case q: QueryRequest =>
        OperationOutcome.Query(q.target, q.consistency, queryShape(state, rng))
      case s: ScanRequest =>
        OperationOutcome.Scan(s.target, s.consistency, scanShape(state, rng))
      case other =>
        throw new IllegalArgumentException(s"the thermostat single-region workload uses put/query/scan, not $other")

  /** Insert (None) vs. overwrite (Some(previous)) by fleet saturation. */
  private def telemetryPrevious(state: TableSummaryState, rng: UniformRandomProvider, tick: Long): Option[Long] =
    val fs = config.fleetSize(tick)
    if state.itemCount <= 0L then None                              // empty table — every write is a new device
    else if state.itemCount >= fs then state.averageItemBytes       // fleet fully seen — every write is an overwrite
    else
      val pNew = (fs - state.itemCount).toDouble / fs.toDouble
      if rng.nextDouble() < pNew then None else state.averageItemBytes

  /** A customer-support query evaluates a small page (2–10 items) of the target. */
  private def queryShape(state: TableSummaryState, rng: UniformRandomProvider): ReadShape =
    if state.itemCount <= 0L then ReadShape(0L, 0L, 0L, 0L)
    else
      val avg       = state.averageItemBytes.getOrElse(config.telemetryItemMeanBytes)
      val evaluated = math.max(1L, math.min(state.itemCount, 2L + rng.nextLong(9L)))         // 2..10
      val returned  = math.max(0L, math.min(evaluated, 1L + rng.nextLong(math.max(1L, evaluated))))
      ReadShape(evaluated, evaluated * avg, returned, returned * avg)

  /** A fleet-dashboard scan evaluates a larger slice (50–250 items) of the target. */
  private def scanShape(state: TableSummaryState, rng: UniformRandomProvider): ReadShape =
    if state.itemCount <= 0L then ReadShape(0L, 0L, 0L, 0L)
    else
      val avg              = state.averageItemBytes.getOrElse(config.telemetryItemMeanBytes)
      val evaluated        = math.max(1L, math.min(state.itemCount, 50L + rng.nextLong(200L)))  // 50..249
      val returnedFraction = 0.2 + rng.nextDouble() * 0.3
      val returned         = math.max(0L, (evaluated * returnedFraction).toLong)
      ReadShape(evaluated, evaluated * avg, returned, returned * avg)
