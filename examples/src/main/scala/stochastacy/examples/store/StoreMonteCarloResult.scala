package stochastacy.examples.store

import stochastacy.core.stats.{Statistic, Statistics}

/** The result of a Monte Carlo run of the store pipeline: one `Statistics` per trial, plus the two
 *  aggregations across them. Retains only per-trial *statistics* (not the full trial results with
 *  their large response vectors) — enough to compute any across-trial summary, bounded in memory.
 *
 *  Two genuinely different questions, both answerable here:
 *   - [[pooled]] — merge every trial's observations into one population. Answers "what does a random
 *     request from a random run look like?" (the underlying per-event distribution).
 *   - [[acrossTrials]] — reduce each trial to one scalar, then summarize those N scalars. Answers
 *     "how does this metric vary run-to-run?" (reliability / worst-case-run / Monte Carlo variance)
 *     — the summary a simulator can produce but repeated real-world runs practically cannot. */
final case class StoreMonteCarloResult(trialCount: Int, perTrial: Vector[Statistics[StoreStatKey]]):

  /** (a) Pooled population: one merged `Statistics` over all trials' observations (associative
   *  `combine`; pooling conserves every observation). */
  def pooled: Statistics[StoreStatKey] =
    perTrial.reduceOption(_ combine _).getOrElse(Statistics.empty)

  /** (b) Distribution across trials of a per-trial `scalar` of `key`'s statistic (e.g. `_.p99`,
   *  `_.mean`). The returned `Statistic` summarizes those per-trial scalars: its `mean`/`stddev`/
   *  `p50`/`p99` are the run-to-run distribution. Trials lacking `key` contribute nothing. */
  def acrossTrials(key: StoreStatKey, scalar: Statistic => Double): Statistic =
    perTrial.flatMap(_.get(key)).map(scalar).foldLeft(Statistic.empty)(_ observe _)
