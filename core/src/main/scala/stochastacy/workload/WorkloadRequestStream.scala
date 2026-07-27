package stochastacy.workload

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.simple.RandomSource
import stochastacy.aws.dynamodb.DynamoDBRequest
import stochastacy.sim.{SimTime, TimedControlEvent, TimedElement}

object WorkloadRequestStream:

  /** Produces an `Iterator[TimedElement[DynamoDBRequest]]` driven entirely by
   *  the workload definition. Output structure: each tick opens with a `Tick`
   *  control event followed by all requests for that tick; a final
   *  `Tick(simulationTicks + 1)` flushes the last window; and
   *  `TimedControlEvent.EndOfTime` is the absolute last element, serving as
   *  the timed-stream terminal sentinel.
   *
   *  Three independent RNGs are split from `rng` per shape:
   *  - `rateRng`     — rate draws (how many requests this tick)
   *  - `paramRng`    — parameter draws (item bytes, item count, etc.)
   *  - `intraTickRng`— arrival-position draws, Uniform(0, 1), assigned to
   *                    `request.intraTick`
   *
   *  Keeping the three RNGs independent means changing a param sampler does
   *  not perturb rate draws or arrival positions, and vice versa. */
  def apply(
    workload:        WorkloadDefinition,
    rng:             UniformRandomProvider,
    simulationTicks: Long
  ): Iterator[TimedElement[DynamoDBRequest]] =

    // Only independent flows have their own rate samplers.
    // Derived flows (follow-on, retry) are handled by FollowOnTransformerStage.
    val independent    = workload.independentFlows
    val n              = independent.size
    val rateRngs       = Vector.fill(n)(RandomSource.KISS.create(rng.nextLong()))
    val paramRngs      = Vector.fill(n)(RandomSource.KISS.create(rng.nextLong()))
    val intraTickRngs  = Vector.fill(n)(RandomSource.KISS.create(rng.nextLong()))

    (1L to simulationTicks).iterator.flatMap { tick =>
      Iterator.single(TimedControlEvent.Tick(SimTime.of(tick)): TimedElement[DynamoDBRequest]) ++
        (0 until n).iterator.flatMap { i =>
          val defn = independent(i).defn
          val (count, _) = defn.rate.sample(tick, rateRngs(i), ())
          Iterator.fill[TimedElement[DynamoDBRequest]](count) {
            val φ = intraTickRngs(i).nextDouble()   // Uniform(0, 1) arrival position
            defn.shape.build(tick, workload.usecase, independent(i).id, paramRngs(i), φ)
          }
        }
    } ++ Iterator[TimedElement[DynamoDBRequest]](
      TimedControlEvent.Tick(SimTime.of(simulationTicks + 1L)),
      TimedControlEvent.EndOfTime
    )

