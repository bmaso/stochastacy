package stochastacy.aws.dynamodb

/**
 * An immutable, deterministic TTL expiry model. Item writes are bucketed per tick into a circular buffer
 * of `ttlPeriodTicks + 1` slots; at each tick boundary the slot filled `ttlPeriodTicks` ticks ago is
 * drained to yield the items expiring now. This is the functional counterpart of the legacy mutable
 * `SimpleTtlSampler` — every transition returns a new buffer, so it threads through the immutable
 * [[TableState]] and stays pure and reproducible.
 *
 * Intermediate deletes are approximated by removing one item from the soonest-to-expire non-empty slot (a
 * proxy for "this item would have expired next and was deleted early"), exactly as the legacy did. Backed
 * by `Vector`, whose `updated` is ~O(log n), so the per-write copy cost stays negligible even at
 * `ttlPeriodTicks = 720`.
 */
final case class TtlRingBuffer(ttlPeriodTicks: Int, counts: Vector[Long], bytes: Vector[Long]):
  private def size: Int             = ttlPeriodTicks + 1
  private def slot(tick: Long): Int = (((tick % size) + size) % size).toInt

  /** Record one item of `itemBytes` bytes written at `tick`, into that tick's slot. */
  def recordWrite(itemBytes: Long, tick: Long): TtlRingBuffer =
    val i = slot(tick)
    copy(counts = counts.updated(i, counts(i) + 1L), bytes = bytes.updated(i, bytes(i) + itemBytes))

  /** Approximate an intermediate delete: decrement the soonest-to-expire non-empty slot (by its average
   *  item size, clamped at zero). A no-op when every slot is empty. */
  def recordDelete(tick: Long): TtlRingBuffer =
    (0 until size).iterator.map(o => slot(tick - ttlPeriodTicks + o)).find(counts(_) > 0L) match
      case Some(i) =>
        val avg = bytes(i) / counts(i)
        copy(counts = counts.updated(i, counts(i) - 1L), bytes = bytes.updated(i, math.max(0L, bytes(i) - avg)))
      case None => this

  /** Drain the slot filled `ttlPeriodTicks` ticks ago: the `(count, bytes)` expiring at `tick`, plus the
   *  buffer with that slot cleared. */
  def expire(tick: Long): (Long, Long, TtlRingBuffer) =
    val i = slot(tick - ttlPeriodTicks)
    (counts(i), bytes(i), copy(counts = counts.updated(i, 0L), bytes = bytes.updated(i, 0L)))

object TtlRingBuffer:
  /** An empty buffer for a `ttlPeriodTicks`-tick TTL (`ttlPeriodTicks >= 1`). */
  def empty(ttlPeriodTicks: Int): TtlRingBuffer =
    require(ttlPeriodTicks >= 1, s"ttlPeriodTicks must be >= 1, got $ttlPeriodTicks")
    val slots = ttlPeriodTicks + 1
    TtlRingBuffer(ttlPeriodTicks, Vector.fill(slots)(0L), Vector.fill(slots)(0L))
