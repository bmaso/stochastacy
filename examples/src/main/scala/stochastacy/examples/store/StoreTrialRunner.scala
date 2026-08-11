package stochastacy.examples.store

import scala.concurrent.{ExecutionContext, Future}

import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Sink
import stochastacy.core.component.{ScheduleReleaseTransducer, Timed}
import stochastacy.core.run.TrialRunner
import stochastacy.core.stats.Statistics
import stochastacy.core.stream.TickFraming
import stochastacy.sim.{TimedControlEvent, TimedElement}

/** Wires the store simulator for a single trial and folds its own consumption facts into
 *  per-(use-case, metric) statistics — the problem-specific runner the store owns, built on the
 *  generic `TrialRunner` plumbing and `core.stats` base types.
 *
 *  A master seed is split into independent workload/sampler RNGs; the whole trial is deterministic
 *  given `seed`. */
object StoreTrialRunner:

  def run(
    workloadCfg:     StoreWorkloadConfig,
    storeCfg:        StoreConfig,
    seed:            Long,
    simulationTicks: Long
  )(using system: ActorSystem): Future[StoreTrialResult] =
    val master      = RandomSource.KISS.create(seed)
    val workloadRng = RandomSource.KISS.create(master.nextLong())
    val samplerRng  = RandomSource.KISS.create(master.nextLong())

    val requests  = StoreWorkload.requests(workloadCfg, workloadRng, simulationTicks)
    val source    = TickFraming.frameSource(requests.iterator, simulationTicks)
    val component = ScheduleReleaseTransducer.componentOf(new StoreSampler(storeCfg), samplerRng)

    // Fold the store's own consumption stream into statistics keyed by (use-case, metric).
    val statsSink: Sink[TimedElement[Timed[Consumption]], Future[Statistics[StoreStatKey]]] =
      Sink.fold(Statistics.empty[StoreStatKey]) { (acc, elem) =>
        elem match
          case t: Timed[Consumption] @unchecked =>
            StoreStats.observations(t.event).foldLeft(acc) { case (a, (metric, value)) =>
              a.observe(StoreStatKey(t.usecase.toString, metric), value)
            }
          case _: TimedControlEvent => acc
      }

    given ExecutionContext = system.dispatcher
    TrialRunner.run(source, component, statsSink).map { case (cr, stats) =>
      StoreTrialResult(cr.finalState, simulationTicks, cr.residue, stats)
    }
