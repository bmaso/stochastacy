package stochastacy.aws.examples.hotkey

import scala.collection.mutable

import org.apache.commons.rng.UniformRandomProvider

import stochastacy.aws.dynamodb.{DynamoDbRequest, GetItemRequest, PutItemRequest}
import stochastacy.core.component.Timed
import stochastacy.core.sampler.PoissonSampler
import stochastacy.sim.SimTime

/**
 * The hot-key arrivals generator. Each tick draws a Poisson count of puts and of gets and emits them
 * phi-shuffled within the tick; the requests carry no key (the [[HotKeyBehavior]] assigns each to a hot or
 * cold partition), so the same arrival stream drives every arm — the skew and the adaptive/heat-split
 * toggles are all downstream of these arrivals.
 */
object HotKeyWorkload:

  def arrivals(config: HotKeyConfig, rng: UniformRandomProvider): Vector[Timed[DynamoDbRequest]] =
    val putRate = PoissonSampler.constant(config.putsPerTick)
    val getRate = PoissonSampler.constant(config.getsPerTick)

    val out  = Vector.newBuilder[Timed[DynamoDbRequest]]
    var tick = 1L
    while tick <= config.simulationTicks do
      val perTick = mutable.ArrayBuffer.empty[(Double, DynamoDbRequest)]
      def add(payload: DynamoDbRequest): Unit = perTick += ((rng.nextDouble(), payload))

      var p = putRate.sample(tick, rng, ())._1
      while p > 0 do { add(PutItemRequest(config.itemBytes)); p -= 1 }
      var g = getRate.sample(tick, rng, ())._1
      while g > 0 do { add(GetItemRequest); g -= 1 }

      perTick.sortInPlaceBy(_._1)
      perTick.foreach { case (phi, payload) => out += Timed(payload, SimTime.of(tick), phi, config.scenarioId) }
      tick += 1

    out.result()
