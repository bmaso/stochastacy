package stochastacy.examples.store

/** Key for a store statistic: a request use-case paired with a metric name. */
final case class StoreStatKey(usecase: String, metric: String)

/** Store-specific interpretation of consumption facts as `(metric, value)` observations — the
 *  domain knowledge that turns structured `Consumption` into numbers the store runner folds into
 *  statistics. `StorageDelta` is intentionally not observed: it is signed, and `StoreState.totalBytes`
 *  already captures storage. */
object StoreStats:

  def observations(c: Consumption): Seq[(String, Double)] = c match
    case RequestServiced(latency) => Seq("latency"         -> latency)
    case WorkPerformed(items, bytes) =>
      Seq("work.items" -> items.toDouble, "work.bytes" -> bytes.toDouble)
    case DataReturned(items, bytes) =>
      Seq("returned.items" -> items.toDouble, "returned.bytes" -> bytes.toDouble)
    case StorageDelta(_) => Seq.empty
