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
        // In commands mode (transactional writes configured) a put is an append (like a transaction sub-item),
        // so the useTransactions=false baseline matches the transactions footprint; otherwise telemetry's
        // insert-or-overwrite saturation applies.
        val previous = if config.transactWriteItemsPerItemBytes.isDefined then None else telemetryPrevious(state, rng, tick)
        OperationOutcome.Put(writtenItemBytes = itemBytes, previousItemBytes = previous)
      case q: QueryRequest =>
        OperationOutcome.Query(q.target, q.consistency, queryShape(state, rng))
      case s: ScanRequest =>
        OperationOutcome.Scan(s.target, s.consistency, scanShape(state, rng))
      case TransactWriteItemsRequest(perItemBytes) =>
        // A device-command dispatch: each sub-item (status update + audit entry) is a new record (insert),
        // its size drawn from the configured bytes ± the telemetry byte variance (matching the legacy).
        OperationOutcome.TransactWrite(perItemBytes.map { b =>
          val v     = config.telemetryItemBytesVariance
          val scale = 1.0 - v + rng.nextDouble() * 2.0 * v
          TableMechanics.TransactWriteItem(writtenItemBytes = math.max(1L, (b * scale).toLong), previousItemBytes = None)
        })
      case other =>
        throw new IllegalArgumentException(s"the thermostat workload uses put/query/scan/transact-write, not $other")

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
