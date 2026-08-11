package stochastacy.core.component

/** Diagnostic summary of outputs still pending at `EndOfTime` — i.e. scheduled past the simulation
 *  horizon and therefore never emitted on the streams. A large residue signals the horizon may be
 *  truncating events. Counts are split by output plane. */
final case class ResidueSummary(responses: Long, consumptions: Long):
  def total: Long = responses + consumptions

object ResidueSummary:
  val empty: ResidueSummary = ResidueSummary(0L, 0L)

/** The materialized result of running one component to `EndOfTime`: its final state plus the
 *  post-horizon residue summary. Produced by [[ScheduleReleaseTransducer]] as its materialized
 *  value. */
final case class ComponentResult[S](finalState: S, residue: ResidueSummary)
