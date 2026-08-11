package stochastacy.examples.store

import scala.concurrent.Future

import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.actor.ActorSystem
import stochastacy.core.component.ScheduleReleaseTransducer
import stochastacy.core.run.{SingleTrialRunner, TrialResult}
import stochastacy.core.stream.TickFraming

/** Wires the store simulator for a single trial: draw the workload, frame it, feed the datastore
 *  component (transducer ∘ StoreSampler), and produce a `TrialResult[StoreState]`.
 *
 *  A master seed is split into independent workload/sampler RNGs so the two draw streams don't
 *  perturb each other; the whole trial is deterministic given `seed`. */
object StoreTrialRunner:

  def run(
    workloadCfg:     StoreWorkloadConfig,
    storeCfg:        StoreConfig,
    seed:            Long,
    simulationTicks: Long
  )(using system: ActorSystem): Future[TrialResult[StoreState]] =
    val master      = RandomSource.KISS.create(seed)
    val workloadRng = RandomSource.KISS.create(master.nextLong())
    val samplerRng  = RandomSource.KISS.create(master.nextLong())

    val requests  = StoreWorkload.requests(workloadCfg, workloadRng, simulationTicks)
    val source    = TickFraming.frameSource(requests.iterator, simulationTicks)
    val component = ScheduleReleaseTransducer.componentOf(new StoreSampler(storeCfg), samplerRng)

    SingleTrialRunner.run(source, component, simulationTicks)
