package stochastacy.aws.dynamodb.table

final case class TtlSamplerContext(tick: Long)

trait TtlSampler:
  def recordWrite(bytes: Long, tick: Long): Unit
  def recordDelete(tick: Long): Unit
  def expiryAt(ctx: TtlSamplerContext): TtlExpirySample

/**
 * Ring-buffer TTL sampler. Tracks item writes per tick in a circular buffer of
 * `ttlPeriodTicks + 1` slots. At each tick boundary, drains the slot that was
 * filled `ttlPeriodTicks` ticks ago to produce the set of items expiring now.
 *
 * Intermediate deletes are approximated by removing one item from the
 * soonest-to-expire non-empty slot — a stochastic proxy for "this item would
 * have expired next and was deleted early."
 *
 * Per-GSI/LSI storage freed is estimated as a fixed byte-per-item rate
 * configured at construction time.
 */
class SimpleTtlSampler(
  ttlPeriodTicks: Int,
  gsiFreedBytesPerItem: Map[String, Long] = Map.empty,
  lsiFreedBytesPerItem: Map[String, Long] = Map.empty
) extends TtlSampler:
  require(ttlPeriodTicks >= 1, "ttlPeriodTicks must be >= 1")

  private val size       = ttlPeriodTicks + 1
  private val itemCounts = Array.fill(size)(0L)
  private val byteTotals = Array.fill(size)(0L)

  private def slotIdx(tick: Long): Int = ((tick % size) + size).toInt % size

  override def recordWrite(bytes: Long, tick: Long): Unit =
    val i = slotIdx(tick)
    itemCounts(i) += 1L
    byteTotals(i) += bytes

  override def recordDelete(tick: Long): Unit =
    // Walk from the oldest pending slot (the one expiring first) toward
    // the current slot, decrementing the first non-empty slot found.
    var found  = false
    var offset = 0
    while !found && offset < size do
      val i = slotIdx(tick - ttlPeriodTicks + offset)
      if itemCounts(i) > 0L then
        val avgBytes = byteTotals(i) / itemCounts(i)
        itemCounts(i) -= 1L
        byteTotals(i) = math.max(0L, byteTotals(i) - avgBytes)
        found = true
      offset += 1

  override def expiryAt(ctx: TtlSamplerContext): TtlExpirySample =
    val i     = slotIdx(ctx.tick - ttlPeriodTicks)
    val count = itemCounts(i)
    val bytes = byteTotals(i)
    itemCounts(i) = 0L
    byteTotals(i) = 0L
    if count <= 0L then TtlExpirySample.empty
    else
      TtlExpirySample(
        expiredItemCount    = count,
        baseTableBytesFreed = bytes,
        gsiStorageFreed     = gsiFreedBytesPerItem.view.mapValues(_ * count).toMap,
        lsiStorageFreed     = lsiFreedBytesPerItem.view.mapValues(_ * count).toMap
      )
