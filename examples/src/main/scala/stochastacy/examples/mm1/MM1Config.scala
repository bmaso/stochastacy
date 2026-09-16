package stochastacy.examples.mm1

/**
 * The MM1 demo's parameters: sessions arrive Poisson(`sessionsPerTick`), each page is served by one FIFO server at
 * `Exp(serviceRate)`, and after each page the client requests another with probability `continueProb` — immediately,
 * or after `Exp(thinkTimeMean)` when a think time is set.
 *
 * This is M/M/1 with Bernoulli feedback: the server's offered load is the *effective* rate [[lambdaEff]] =
 * `λ / (1 − p)`, and its utilisation is [[rho]]. A tick is the time unit for every rate and duration here.
 *
 * Measurement runs over `(warmupTicks, simulationTicks]` — the queue starts empty, so the opening transient is
 * excluded rather than averaged in.
 */
final case class MM1Config(
  scenarioId:      String         = "mm1-immediate",
  sessionsPerTick: Double         = 40.0,  // λ
  serviceRate:     Double         = 125.0, // μ
  continueProb:    Double         = 0.6,   // p
  thinkTimeMean:   Option[Double] = None,  // mean of the exponential think time (None = next page immediately)
  simulationTicks: Long           = 300L,
  warmupTicks:     Long           = 60L,   // 20 % of the horizon
  queueLevels:     Int            = 12,    // queue-length slots: N = 0 … queueLevels−2 each, last slot N ≥ queueLevels−1
  pageLevels:      Int            = 6,     // pages-per-session slots: 1 … pageLevels−1 each, last slot ≥ pageLevels
  cohortMarginTicks: Double       = 20.0,  // session cohort stops starting this long before the horizon (see inCohort)
  trialCount:      Int            = 200,
  parallelism:     Int            = 8
):
  require(scenarioId.nonEmpty, "scenarioId must be non-empty")
  require(sessionsPerTick > 0.0, s"sessionsPerTick must be positive, got $sessionsPerTick")
  require(serviceRate > 0.0, s"serviceRate must be positive, got $serviceRate")
  require(continueProb >= 0.0 && continueProb < 1.0, s"continueProb must be in [0, 1), got $continueProb")
  require(thinkTimeMean.forall(_ > 0.0), s"thinkTimeMean must be positive when set, got $thinkTimeMean")
  require(simulationTicks > 0L, s"simulationTicks must be positive, got $simulationTicks")
  require(warmupTicks >= 0L && warmupTicks < simulationTicks, s"warmupTicks must be in [0, simulationTicks), got $warmupTicks")
  require(queueLevels >= 2, s"queueLevels must be at least 2 (one level plus the tail), got $queueLevels")
  require(pageLevels >= 2, s"pageLevels must be at least 2 (one count plus the tail), got $pageLevels")
  require(trialCount > 0, s"trialCount must be positive, got $trialCount")
  require(parallelism > 0, s"parallelism must be positive, got $parallelism")

  /** The rate of page requests reaching the server: each session makes `1/(1−p)` of them on average. */
  def lambdaEff: Double = sessionsPerTick / (1.0 - continueProb)

  /** Server utilisation. */
  def rho: Double = lambdaEff / serviceRate

  require(rho < 1.0, f"the server must be stable: rho = $rho%.3f (lambdaEff ${lambdaEff}%.1f vs serviceRate $serviceRate%.1f)")

  /** Conceptual time at which measurement starts — framing runs ticks `1 … simulationTicks`, so tick `t` covers
   *  `[t, t+1)` and the measured span is `[warmupTicks + 1, simulationTicks + 1)`. */
  def measuredFrom:  Double = warmupTicks.toDouble + 1.0
  def measuredUntil: Double = simulationTicks.toDouble + 1.0
  def measuredTicks: Double = measuredUntil - measuredFrom

  def measured(conceptualTime: Double): Boolean = conceptualTime >= measuredFrom && conceptualTime < measuredUntil

  /** The last start time for a session to join the per-session cohort: `cohortMarginTicks` before the horizon. */
  def cohortUntil: Double = measuredUntil - cohortMarginTicks

  /**
   * Whether a session that started at `startedAt` belongs to the per-session cohort. Sessions still running at the
   * horizon cannot be measured, and the long ones are the ones most likely to still be running — so simply excluding
   * unfinished sessions under-represents long sessions and biases pages per session and session duration low (measured
   * at −10σ on pages per session at ρ = 0.9 over 8 000 trials). Stopping the cohort's *starts* a margin before the
   * horizon gives every cohort session time to finish, which removes the selection instead of measuring around it.
   *
   * The default margin is measured, not guessed: near saturation the queue makes long excursions and a session caught
   * in one re-queues every page behind a long line. At ρ = 0.9, 375 of 11.7 M sessions ran past 5 ticks and the longest
   * took 10.85 — so a 5-tick margin left about two cohort sessions unfinished per thousand trials, while 20 ticks is
   * nearly double the longest session observed.
   */
  def inCohort(startedAt: Double): Boolean = startedAt >= measuredFrom && startedAt < cohortUntil

  require(cohortMarginTicks >= 0.0, s"cohortMarginTicks must be non-negative, got $cohortMarginTicks")
  require(cohortUntil > measuredFrom,
    s"the cohort window is empty: measured window [$measuredFrom, $measuredUntil) minus a $cohortMarginTicks-tick margin")
