package stochastacy.examples.store

import org.json4s.JObject
import org.json4s.JsonDSL.*
import org.json4s.jackson.JsonMethods.{compact, render}
import stochastacy.core.stats.Statistic

/** Export for a store Monte Carlo run: JSONL for machines, a short text summary for humans. Both are
 *  store-specific artifacts (core imposes no observation semantics). The JSONL carries two record
 *  kinds — `pooled` (the per-`(usecase, metric, window)` population statistic) and `acrossTrials`
 *  (the run-to-run distribution of a per-trial scalar) — the two aggregation flavors from Slice 7.
 *
 *  The summary spells out the three phenomena the capstone exists to demonstrate: the cardinality-
 *  driven cost rise over a run, the deep-offset cost cliff, and throttling under load. */
object StoreReport:

  private def statObj(s: Statistic): JObject =
    ("count" -> s.count) ~ ("mean" -> s.mean) ~ ("p50" -> s.p50) ~ ("p99" -> s.p99) ~ ("stddev" -> s.stddev)

  /** One `pooled` JSON object per `(usecase, metric, window)`. */
  def pooledLines(r: StoreMonteCarloResult): Seq[String] =
    val pooled = r.pooled
    pooled.keys.toSeq.sortBy(k => (k.usecase, k.metric, k.window)).flatMap { k =>
      pooled.get(k).map { s =>
        val header: JObject =
          ("kind" -> "pooled") ~ ("usecase" -> k.usecase) ~ ("metric" -> k.metric) ~ ("window" -> k.window)
        compact(render(header ~ statObj(s)))
      }
    }

  /** One `acrossTrials` JSON object per `(usecase, metric, window)` for a representative per-trial
   *  scalar: p99 for latency-like metrics, mean (= rate) for the 0/1 `throttled` metric. */
  def acrossTrialLines(r: StoreMonteCarloResult): Seq[String] =
    r.pooled.keys.toSeq.sortBy(k => (k.usecase, k.metric, k.window)).flatMap { k =>
      scalarFor(k.metric).map { case (name, f) =>
        val d = r.acrossTrials(k, f)
        val header: JObject =
          ("kind" -> "acrossTrials") ~ ("usecase" -> k.usecase) ~ ("metric" -> k.metric) ~
            ("window" -> k.window) ~ ("scalar" -> name) ~ ("trials" -> r.trialCount)
        compact(render(header ~ statObj(d)))
      }
    }

  private def scalarFor(metric: String): Option[(String, Statistic => Double)] =
    if metric == "throttled" then Some("mean" -> (_.mean))
    else if metric.endsWith("latency") then Some("p99" -> (_.p99))
    else None

  def jsonl(r: StoreMonteCarloResult): String =
    (pooledLines(r) ++ acrossTrialLines(r)).mkString("\n")

  // --- text summary: the three capstone findings, derived from the pooled statistics ---

  /** Combine a `(usecase, metric)`'s statistic across all windows (whole-run view). */
  private def combined(r: StoreMonteCarloResult, usecase: String, metric: String): Option[Statistic] =
    r.pooled.keys.filter(k => k.usecase == usecase && k.metric == metric).flatMap(r.pooled.get).reduceOption(_ combine _)

  /** A `(usecase, metric)`'s per-window statistics, ascending by window. */
  private def byWindow(r: StoreMonteCarloResult, usecase: String, metric: String): Seq[(Int, Statistic)] =
    r.pooled.keys.filter(k => k.usecase == usecase && k.metric == metric).toSeq
      .flatMap(k => r.pooled.get(k).map(k.window -> _)).sortBy(_._1)

  def summary(r: StoreMonteCarloResult): String =
    val sb = new StringBuilder
    sb ++= s"Store simulator — Monte Carlo summary (${r.trialCount} trials)\n"

    val rise = byWindow(r, "report", "latency")
    if rise.sizeIs >= 2 then
      val (w0, s0) = rise.head
      val (wN, sN) = rise.last
      val pct = if s0.mean == 0.0 then 0.0 else (sN.mean - s0.mean) / s0.mean * 100.0
      sb ++= f"  cardinality rise : report latency window $w0 mean=${s0.mean}%.4f -> window $wN mean=${sN.mean}%.4f (${pct}%+.1f%%)\n"

    (combined(r, "list.offset", "latency"), combined(r, "list.keyset", "latency")) match
      case (Some(off), Some(key)) =>
        val ratio = if key.p99 == 0.0 then Double.NaN else off.p99 / key.p99
        sb ++= f"  deep-offset cliff: list.offset latency p99=${off.p99}%.4f vs list.keyset p99=${key.p99}%.4f (${ratio}%.1fx)\n"
      case _ => ()

    val rates = r.pooled.keys.filter(_.metric == "throttled").map(_.usecase).toSeq.distinct.sorted
      .flatMap(uc => combined(r, uc, "throttled").map(uc -> _.mean))
    if rates.nonEmpty then
      val rendered = rates.map { case (uc, rt) => f"$uc=${rt * 100.0}%.1f%%" }.mkString(", ")
      sb ++= f"  throttling       : $rendered\n"

    sb.result()
