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
 * empty and the closed forms this demo is checked against are steady-state. Per-session averages are taken over the
 * cohort defined by [[MM1Config.inCohort]] — sessions that start a margin before the horizon, so every one has time to
 * finish; `sessionsInFlight` counts cohort sessions that nevertheless had not, and is expected to be zero.
 */
object MM1TrialRunner:

  /** Running totals over the measured window. */
  private final case class Acc(
    sessions:        Long,
    sessionPages:    Long,
    sessionDuration: Double,
    pages:           Long,
    pageSojourn:     Double,
    inSystem:        Double,
    busy:            Double,
    windows:         Long,           // window integrals actually received — the divisor for the per-window integrals
    levelTimes:      Vector[Double], // time at each number-in-system slot, summed over measured windows
    pageCounts:      Vector[Long]    // cohort sessions per page-count slot
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

    // Materialized so the trial can count the sessions that *started* in the cohort — the population the per-session
    // averages are taken over, and the basis for the in-flight count.
    val arrivals        = MM1Workload.arrivals(config, RandomSource.KISS.create(workloadSeed)).toVector
    val startedInCohort = arrivals.count(a => config.inCohort(a.eventTime.ticks.toDouble + a.intraTick)).toLong
    val source          = TickFraming.frameSource(arrivals.iterator, config.simulationTicks)

    val initial = Acc(0L, 0L, 0.0, 0L, 0.0, 0.0, 0.0, 0L,
                      Vector.fill(config.queueLevels)(0.0), Vector.fill(config.pageLevels)(0L))

    val fold = Sink.fold[Acc, TimedElement[Timed[MM1Fact]]](initial) { (acc, element) =>
      element match
        case t: Timed[MM1Fact] @unchecked =>
          val at = t.eventTime.ticks.toDouble + t.intraTick
          t.event match
            case MM1Fact.SessionCompleted(pages, startedAt, duration) if config.inCohort(startedAt) =>
              val slot = math.min(pages, config.pageLevels) - 1
              acc.copy(sessions = acc.sessions + 1L, sessionPages = acc.sessionPages + pages,
                       sessionDuration = acc.sessionDuration + duration,
                       pageCounts = acc.pageCounts.updated(slot, acc.pageCounts(slot) + 1L))
            case MM1Fact.PageServed(sojourn) if config.measured(at) =>
              acc.copy(pages = acc.pages + 1L, pageSojourn = acc.pageSojourn + sojourn)
            case MM1Fact.WindowIntegral(windowStart, inSystem, busy) if config.measured(windowStart.toDouble) =>
              acc.copy(inSystem = acc.inSystem + inSystem, busy = acc.busy + busy, windows = acc.windows + 1L)
            case MM1Fact.WindowLevels(windowStart, times) if config.measured(windowStart.toDouble) =>
              acc.copy(levelTimes = acc.levelTimes.lazyZip(times).map(_ + _))
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
        sessionsInFlight = math.max(0L, startedInCohort - acc.sessions),
        pagesMeasured    = acc.pages,
        // Each window is one tick long, so time at a level ÷ windows received is the fraction of time at that level.
        queueLevelFractions = acc.levelTimes.map(t => ratio(t, acc.windows)),
        pagesDistribution   = acc.pageCounts.map(c => ratio(c.toDouble, acc.sessions))
      )
    }

  private def ratio(total: Double, count: Long): Double = if count == 0L then 0.0 else total / count.toDouble
