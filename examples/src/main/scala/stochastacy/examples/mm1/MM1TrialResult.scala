package stochastacy.examples.mm1

/**
 * One trial's measurements, all restricted to the measured window (the warm-up prefix is excluded everywhere).
 *
 * The session cohort is sessions that **started** in the measured window and finished before the horizon;
 * `sessionsInFlight` counts those that started in it but had not finished, and are therefore excluded rather than
 * counted short.
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
  windowsMeasured:  Long // tick windows integrated for meanInSystem / busyFraction; the final window is residue
)
