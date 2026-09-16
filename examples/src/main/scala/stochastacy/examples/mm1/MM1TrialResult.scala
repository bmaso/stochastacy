package stochastacy.examples.mm1

/**
 * One trial's measurements, all restricted to the measured window (the warm-up prefix is excluded everywhere).
 *
 * The session cohort is sessions that **started** in the measured window and at least `cohortMarginTicks` before the
 * horizon ([[MM1Config.inCohort]]), so every cohort session has time to finish; `sessionsInFlight` counts any that
 * nevertheless had not, and is expected to be zero.
 */
final case class MM1TrialResult(
  trialId:          Int,
  pagesPerSession:  Double,
  sessionDuration:  Double,
  pageTime:         Double,
  meanInSystem:     Double,
  busyFraction:     Double,
  pageRate:         Double,
  sessionsMeasured: Long,
  sessionsInFlight: Long,
  pagesMeasured:    Long,
  windowsMeasured:  Long, // tick windows integrated for meanInSystem / busyFraction; the final window is residue
  queueLevelFractions: Vector[Double], // fraction of time at N = 0, 1, …; last slot N ≥ queueLevels − 1
  pagesDistribution:   Vector[Double]  // fraction of cohort sessions with 1, 2, … pages; last slot ≥ pageLevels
)
