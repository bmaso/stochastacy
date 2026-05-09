package stochastacy.examples.thermostatfleet

import org.apache.commons.rng.UniformRandomProvider
import stochastacy.aws.dynamodb.*
import stochastacy.aws.dynamodb.table.*
import stochastacy.sim.SimTime

final class ThermostatFleetBehavior(
  config: ThermostatFleetScenarioConfig,
  rng: UniformRandomProvider,
  initialDeviceCount: Long,
  deviceGrowthPerTick: Double
) extends UseCaseSampler[TableState]:

  private def currentFleetSize(tick: Long): Long =
    math.max(1L, initialDeviceCount + (deviceGrowthPerTick * tick).toLong)

  override def putItem(request: PutItemRequest, ctx: SamplerContext[TableState]): PutItemSample =
    val state = ctx.state
    val fleetSize = currentFleetSize(ctx.currentTick)
    val meanBytes = config.telemetryItemMeanBytes.toDouble
    val variance = config.telemetryItemBytesVariance
    val scale = 1.0 - variance + (rng.nextDouble() * 2.0 * variance)
    val writtenBytes = math.max(1L, (meanBytes * scale).toLong)
    val estimatedCollectionBytes = estimateItemCollectionBytes(state, fleetSize)

    // Each telemetry write either creates a brand-new device record or overwrites an existing one.
    // The probability that a given write lands on a device not yet in the table is approximately
    // (fleetSize - itemCount) / fleetSize.  New-device writes must be modelled as inserts
    // (previousItemBytes = None) so that KeysOnly/Include GSI entries are correctly charged;
    // existing-device writes are updates where the GSI key attribute (customerId) is unchanged,
    // so previousItemBytes = averageItemBytes lets IndexMaintenanceMath resolve them to NoOp
    // (0 WCU) for those projections.
    val previousItemBytes =
      if state.itemCount <= 0L then
        None
      else if fleetSize <= 0L || state.itemCount >= fleetSize then
        state.averageItemBytes
      else
        val pNew = (fleetSize - state.itemCount).toDouble / fleetSize
        if rng.nextDouble() < pNew then None else state.averageItemBytes

    ThermostatPutItemSample(
      writtenItemBytes = writtenBytes,
      previousItemBytes = previousItemBytes,
      logicalPartitionAccess = LogicalPartitionAccess.SingleLogicalPartitionKey(nextDeviceKey(fleetSize)),
      currentItemCollectionBytes = estimatedCollectionBytes
    )

  override def query(request: QueryRequest, ctx: SamplerContext[TableState]): QuerySample =
    val state = ctx.state
    val fleetSize = currentFleetSize(ctx.currentTick)
    val avgBytes = state.averageItemBytes.getOrElse(config.telemetryItemMeanBytes)
    val evaluatedCount = math.max(1L, math.min(state.itemCount, 2L + rng.nextLong(9L)))
    val returnedCount = math.max(0L, math.min(evaluatedCount, 1L + rng.nextLong(math.max(1L, evaluatedCount))))

    QuerySample(
      evaluatedItemCount = evaluatedCount,
      evaluatedBytes = evaluatedCount * avgBytes,
      returnedItemCount = returnedCount,
      returnedBytes = returnedCount * avgBytes,
      logicalPartitionAccess = LogicalPartitionAccess.SingleLogicalPartitionKey(nextCustomerKey(fleetSize))
    )

  override def scan(request: ScanRequest, ctx: SamplerContext[TableState]): ScanSample =
    val state = ctx.state
    val avgBytes = state.averageItemBytes.getOrElse(config.telemetryItemMeanBytes)
    val evaluatedCount = math.max(1L, math.min(state.itemCount, 50L + rng.nextLong(200L)))
    val returnedFraction = 0.2 + rng.nextDouble() * 0.3
    val returnedCount = math.max(0L, (evaluatedCount * returnedFraction).toLong)

    ScanSample(
      evaluatedItemCount = evaluatedCount,
      evaluatedBytes = evaluatedCount * avgBytes,
      returnedItemCount = returnedCount,
      returnedBytes = returnedCount * avgBytes,
      logicalPartitionAccess = LogicalPartitionAccess.AllPartitions
    )

  override def transactWriteItems(request: TransactWriteItemsRequest, ctx: SamplerContext[TableState]): TransactWriteItemsSample =
    val fleetSize = currentFleetSize(ctx.currentTick)
    val perItemBytes = request.perItemBytes
    val items: Vector[WriteItemSample] = perItemBytes.map { bytes =>
      val variance = config.telemetryItemBytesVariance
      val scale = 1.0 - variance + (rng.nextDouble() * 2.0 * variance)
      val writtenBytes = math.max(1L, (bytes * scale).toLong)
      ThermostatPutItemSample(
        writtenItemBytes = writtenBytes,
        previousItemBytes = None,
        logicalPartitionAccess = LogicalPartitionAccess.SingleLogicalPartitionKey(nextDeviceKey(fleetSize)),
        currentItemCollectionBytes = 0L
      )
    }
    ThermostatTransactWriteItemsSample(items)

  private def estimateItemCollectionBytes(state: TableState, fleetSize: Long): Long =
    if fleetSize <= 0L || state.itemCount <= 0L then 0L
    else
      val avgBytesPerItem = state.averageItemBytes.getOrElse(config.telemetryItemMeanBytes)
      val itemsPerDevice = state.itemCount / fleetSize
      math.max(0L, avgBytesPerItem * itemsPerDevice)

  private def nextDeviceKey(fleetSize: Long): String =
    val deviceId = if fleetSize > 0L then rng.nextLong(fleetSize) else 0L
    s"device-$deviceId"

  private def nextCustomerKey(fleetSize: Long): String =
    val customerCount = math.max(1L, fleetSize / 10L)
    s"customer-${rng.nextLong(customerCount)}"


private final case class ThermostatPutItemSample(
  override val writtenItemBytes: Long,
  override val previousItemBytes: Option[Long],
  override val logicalPartitionAccess: LogicalPartitionAccess,
  override val currentItemCollectionBytes: Long
) extends PutItemSample

private final case class ThermostatTransactWriteItemsSample(
  override val items: Vector[WriteItemSample]
) extends TransactWriteItemsSample
