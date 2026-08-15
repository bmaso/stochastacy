package stochastacy.examples.store.v2

import stochastacy.examples.store.{StoreMonteCarloResult, StoreStatKey}

/** Store Demo V2's human summary: the per-gate terminal-outcome rates derived from the in-band response
 *  outcomes (`outcome.served` / `outcome.throttled` / `outcome.chaos`). JSONL export reuses the original
 *  demo's `StoreReport.jsonl` (both operate on the shared `StoreMonteCarloResult`); this adds only the
 *  gate-focused text summary. */
object StoreV2Report:

  /** Pooled rate of an outcome metric across all use-cases and windows (mean of the 0/1 metric). */
  private def rate(r: StoreMonteCarloResult, metric: String): Double =
    r.pooled.keys.filter(_.metric == metric).flatMap(r.pooled.get).reduceOption(_ combine _).map(_.mean).getOrElse(0.0)

  private def rateByUsecase(r: StoreMonteCarloResult, metric: String): Seq[(String, Double)] =
    r.pooled.keys.filter(_.metric == metric).map(_.usecase).toSeq.distinct.sorted.flatMap { uc =>
      r.pooled.keys.filter(k => k.usecase == uc && k.metric == metric).flatMap(r.pooled.get)
        .reduceOption(_ combine _).map(uc -> _.mean)
    }

  def summary(r: StoreMonteCarloResult): String =
    val sb = new StringBuilder
    sb ++= s"Store Demo V2 — Monte Carlo summary (${r.trialCount} trials)\n"
    sb ++= "  gate outcome rates (of all requests):\n"
    sb ++= f"    served:          ${rate(r, "outcome.served") * 100.0}%.1f%%\n"
    sb ++= f"    throttled (429): ${rate(r, "outcome.throttled") * 100.0}%.1f%%\n"
    sb ++= f"    chaos (503):     ${rate(r, "outcome.chaos") * 100.0}%.1f%%\n"

    val throttledByUc = rateByUsecase(r, "outcome.throttled")
    val chaosByUc     = rateByUsecase(r, "outcome.chaos")
    if throttledByUc.nonEmpty then
      sb ++= s"  throttled (429) by use-case: ${throttledByUc.map { case (uc, x) => f"$uc=${x * 100.0}%.1f%%" }.mkString(", ")}\n"
    if chaosByUc.nonEmpty then
      sb ++= s"  chaos (503) by use-case:     ${chaosByUc.map { case (uc, x) => f"$uc=${x * 100.0}%.1f%%" }.mkString(", ")}\n"
    sb.result()
