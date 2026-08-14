package stochastacy.examples.store

/** Key for a store statistic: a request use-case, a metric name, and a coarse time `window` (Slice 8).
 *  `window` defaults to `0`, so 2-arg keys address the single-window (whole-run) case; the runner tags
 *  observations with a real window index only when configured with a finite `windowTicks`. Windowing
 *  is what makes the cardinality-driven cost rise *over a run* observable — early vs late windows. */
final case class StoreStatKey(usecase: String, metric: String, window: Int = 0)

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

  /** Admission facts as `(metric, value)` observations. `throttled` is a 0/1 per request, so its
   *  mean is the throttle rate and its count is the number of requests that reached admission. */
  def admissionObservations(c: AdmissionConsumption): Seq[(String, Double)] = c match
    case AdmissionLatency(ticks)   => Seq("admission.latency" -> ticks)
    case AdmissionDecision(thr)    => Seq("throttled" -> (if thr then 1.0 else 0.0))
