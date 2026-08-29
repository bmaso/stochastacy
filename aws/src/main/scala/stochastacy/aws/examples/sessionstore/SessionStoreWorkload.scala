package stochastacy.aws.examples.sessionstore

import scala.collection.mutable

import org.apache.commons.rng.UniformRandomProvider

import stochastacy.aws.dynamodb.{DynamoDbRequest, GetItemRequest, PutItemRequest}
import stochastacy.core.component.Timed
import stochastacy.core.sampler.PoissonSampler
import stochastacy.sim.SimTime

/**
 * The session-store arrivals generator. Each tick draws a Poisson count of session **creates**
 * (`PutItem`, `sessionBytes` each) and a Poisson count of **validations** (`GetItem`), places them at
 * random intra-tick offsets, and orders them within the tick. Self-contained: it bakes in its own tick
 * horizon (`config.simulationTicks`), which the runner frames against the ensemble.
 */
object SessionStoreWorkload:

  def arrivals(config: SessionStoreConfig, rng: UniformRandomProvider): Vector[Timed[DynamoDbRequest]] =
    val createRate   = PoissonSampler.constant(config.sessionsPerTick)
    val validateRate = PoissonSampler.constant(config.validationsPerTick)

    val out  = Vector.newBuilder[Timed[DynamoDbRequest]]
    var tick = 1L
    while tick <= config.simulationTicks do
      val perTick = mutable.ArrayBuffer.empty[(Double, DynamoDbRequest)]

      def emit(count: Int, mk: () => DynamoDbRequest): Unit =
        var i = 0
        while i < count do
          perTick += ((rng.nextDouble(), mk()))
          i += 1

      emit(createRate.sample(tick, rng, ())._1,   () => PutItemRequest(config.sessionBytes))
      emit(validateRate.sample(tick, rng, ())._1, () => GetItemRequest)

      perTick.sortInPlaceBy(_._1)
      perTick.foreach { case (phi, payload) => out += Timed(payload, SimTime.of(tick), phi, config.scenarioId) }
      tick += 1

    out.result()
