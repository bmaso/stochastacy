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
