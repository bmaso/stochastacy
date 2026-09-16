package stochastacy.examples.mm1

import scala.concurrent.{ExecutionContext, Future}

import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Sink

import stochastacy.core.component.Timed
import stochastacy.core.component.circuit.Circuit
import stochastacy.core.run.{SeedSequence, TrialRunner}
import stochastacy.core.stream.TickFraming
import stochastacy.sim.{TimedControlEvent, TimedElement, ticks}

/**
 * Runs one MM1 trial: generate the session arrivals, drive them through the circuit, and fold the consumption plane
 * into [[MM1TrialResult]]. The fold keeps only running accumulators, so a trial never holds its facts — the per-page
 * and per-session facts of a long run stream past.
 *
 * Every measurement is restricted to the measured window; the warm-up prefix is discarded, because the queue starts
 * empty and the closed forms this demo is checked against are steady-state. Sessions that started in the window but
 * had not finished by the horizon are excluded from the per-session averages (they could only read short) and
 * reported as `sessionsInFlight`.
 */
object MM1TrialRunner:

  /** Running totals over the measured window. */
  private final case class Acc(
    sessions:        Long   = 0L,
    sessionPages:    Long   = 0L,
    sessionDuration: Double = 0.0,
    pages:           Long   = 0L,
    pageSojourn:     Double = 0.0,
    inSystem:        Double = 0.0,
    busy:            Double = 0.0,
    windows:         Long   = 0L  // window integrals actually received — the divisor for the two integrals
  )

  def run(config: MM1Config, trialId: Int, trialSeed: Long)(using
    system: ActorSystem,
    ec:     ExecutionContext
  ): Future[MM1TrialResult] =
    val seeds        = SeedSequence.derive(trialSeed, 2)
    val workloadSeed = seeds(0)
    val circuitSeed  = seeds(1)

    val (circuit, _) = MM1Circuit.build(config)
    val component    = Circuit.componentOf(circuit, RandomSource.KISS.create(circuitSeed))

    // Materialized so the trial can count the sessions that *started* in the measured window — the cohort the
    // per-session averages are taken over, and the basis for the in-flight count.
    val arrivals        = MM1Workload.arrivals(config, RandomSource.KISS.create(workloadSeed)).toVector
    val startedInWindow = arrivals.count(a => config.measured(a.eventTime.ticks.toDouble + a.intraTick)).toLong
    val source          = TickFraming.frameSource(arrivals.iterator, config.simulationTicks)

    val fold = Sink.fold[Acc, TimedElement[Timed[MM1Fact]]](Acc()) { (acc, element) =>
      element match
        case t: Timed[MM1Fact] @unchecked =>
          val at = t.eventTime.ticks.toDouble + t.intraTick
          t.event match
            case MM1Fact.SessionCompleted(pages, startedAt, duration) if config.measured(startedAt) =>
              acc.copy(sessions = acc.sessions + 1L, sessionPages = acc.sessionPages + pages,
                       sessionDuration = acc.sessionDuration + duration)
            case MM1Fact.PageServed(sojourn) if config.measured(at) =>
              acc.copy(pages = acc.pages + 1L, pageSojourn = acc.pageSojourn + sojourn)
            case MM1Fact.WindowIntegral(windowStart, inSystem, busy) if config.measured(windowStart.toDouble) =>
              acc.copy(inSystem = acc.inSystem + inSystem, busy = acc.busy + busy, windows = acc.windows + 1L)
            case _ => acc
        case _: TimedControlEvent => acc
    }

    TrialRunner.run(source, component, fold).map { (_, acc) =>
      MM1TrialResult(
        trialId          = trialId,
        pagesPerSession  = ratio(acc.sessionPages.toDouble, acc.sessions),
        sessionDuration  = ratio(acc.sessionDuration, acc.sessions),
        pageTime         = ratio(acc.pageSojourn, acc.pages),
        // Divide the integrals by the windows *received*, not the nominal measured ticks: a window is integrated when
        // it closes and stamped at the next boundary, so the final window — closed by the flush tick — has no later
        // boundary to be released at and is post-horizon residue. Dividing by the nominal count biased both
        // integrals low by (M−1)/M (e.g. busy fraction 0.797 vs ρ = 0.8 over 240 measured ticks).
        meanInSystem     = ratio(acc.inSystem, acc.windows),
        busyFraction     = ratio(acc.busy, acc.windows),
        windowsMeasured  = acc.windows,
        pageRate         = acc.pages.toDouble / config.measuredTicks,
        sessionsMeasured = acc.sessions,
        sessionsInFlight = math.max(0L, startedInWindow - acc.sessions),
        pagesMeasured    = acc.pages
      )
    }

  private def ratio(total: Double, count: Long): Double = if count == 0L then 0.0 else total / count.toDouble
