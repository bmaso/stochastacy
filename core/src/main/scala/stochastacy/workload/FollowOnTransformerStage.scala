package stochastacy.workload

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.statistics.distribution.BinomialDistribution
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow
import stochastacy.aws.dynamodb.{DynamoDBRequest, DynamoDBResponse, ThrottledResponse}
import stochastacy.sim.{TimedControlEvent, TimedElement}
import stochastacy.sim.ticks

import scala.collection.mutable

/**
 * Resolved, lookup-complete description of a single derived flow (FollowOn or Retry).
 * All information needed by FollowOnTransformerStage to generate derived requests at
 * runtime — no further lookup into WorkloadDefinition is required.
 *
 * @param id           The flow id to stamp on emitted requests.
 * @param sourceFlowId The flow id to watch on incoming response events.
 * @param outcome      Which class of response outcome (Success or Throttled) counts.
 * @param proportion   Binomial probability p: count ~ Binomial(n, proportion).
 * @param lagTicks     Ticks of delay between the observed outcome tick and emission tick.
 *                     Must be >= 1.
 * @param shape        RequestShape for the derived requests to emit.
 * @param usecase      Usecase tag to stamp on emitted requests (inherits from workload).
 */
case class ResolvedDerivedFlow(
  id:           String,
  sourceFlowId: String,
  outcome:      OutcomeFilter,
  proportion:   Double,
  lagTicks:     Int,
  shape:        RequestShape,
  usecase:      String
)

/**
 * A Pekko `Flow` that observes a `DynamoDBResponse` timed-event stream and emits derived
 * `DynamoDBRequest` timed-event elements. It counts per-flow-per-outcome responses within
 * each tick window, then on each subsequent Tick boundary it draws Binomial-sampled request
 * counts and emits the derived requests into the correct future tick window, respecting the
 * configured `lagTicks` delay.
 *
 * Only `TimedControlEvent.Tick` elements pass through. `DynamoDBResponse` data events are
 * consumed (counted) but not forwarded — the output type is `TimedElement[DynamoDBRequest]`
 * so responses cannot be forwarded. The Tick pass-through is valid by covariance:
 * `TimedControlEvent <: TimedElement[Nothing] <: TimedElement[DynamoDBRequest]`.
 *
 * Emission timing:
 *   - Counts for tick T are accumulated while processing events between Tick(T) and Tick(T+1).
 *   - On arrival of Tick(T+1):
 *       1. Emit Tick(T+1) itself.
 *       2. For each derived flow with lagTicks == 1: draw and emit derived requests for tick T+1.
 *       3. For each derived flow with lagTicks > 1: enqueue the derived batch for emission at
 *          tick T+lagTicks (stored in `delayQueues` keyed by target tick number).
 *       4. Drain `delayQueues` for any target tick <= T+1 and emit those batches.
 *       5. Reset per-tick outcome counts.
 */
object FollowOnTransformerStage:

  def apply(
    flows: Vector[ResolvedDerivedFlow],
    rng:   UniformRandomProvider
  ): Flow[TimedElement[DynamoDBResponse], TimedElement[DynamoDBRequest], NotUsed] =

    Flow[TimedElement[DynamoDBResponse]].statefulMapConcat { () =>

      // Outcome counts accumulated within the current tick window.
      // Key: (sourceFlowId, OutcomeFilter)
      val outcomeCounts: mutable.Map[(String, OutcomeFilter), Int] = mutable.Map.empty

      // Delay queue for batches destined for a future tick.
      // Key: target tick number (Long); value: ordered batches of derived requests.
      val delayQueues: mutable.Map[Long, mutable.Queue[TimedElement[DynamoDBRequest]]] =
        mutable.Map.empty

      // Current tick number; -1 before the first Tick is received.
      var currentTick: Long = -1L
      // Note: `rng` is shared across all flows and all draws. This is intentional —
      // the stage is single-threaded by the Pekko Streams contract.

      /** Derives a batch of requests for a given derived flow given the count of source
       *  outcomes observed in the just-completed tick. Returns an iterator of
       *  TimedElement[DynamoDBRequest] timestamped at `emitTick`. */
      def deriveRequests(
        flow:     ResolvedDerivedFlow,
        fromTick: Long,
        emitTick: Long
      ): Vector[TimedElement[DynamoDBRequest]] =
        val n = outcomeCounts.getOrElse((flow.sourceFlowId, flow.outcome), 0)
        if n == 0 then Vector.empty
        else
          val count =
            if flow.proportion >= 1.0 then n
            else if flow.proportion <= 0.0 then 0
            else BinomialDistribution.of(n, flow.proportion).createSampler(rng).sample()
          Vector.fill[TimedElement[DynamoDBRequest]](count)(
            flow.shape.build(emitTick, flow.usecase, flow.id, rng, rng.nextDouble())
          )

      /** Drains any batches from delayQueues whose target tick <= drainUpTo. */
      def drainQueues(drainUpTo: Long): Vector[TimedElement[DynamoDBRequest]] =
        val ready = delayQueues.keys.filter(_ <= drainUpTo).toVector.sorted
        ready.flatMap { targetTick =>
          val q = delayQueues.remove(targetTick).getOrElse(mutable.Queue.empty)
          q.toVector
        }

      // The statefulMapConcat function: element => Iterable[out]
      element =>
        element match

          case tick: TimedControlEvent.Tick =>
            val t = tick.eventTime.ticks
            // The tick we just received advances the window. Compute the previous tick
            // (the window whose counts we just finished accumulating). For the very first
            // tick we skip derivation since there are no prior counts.
            val prevTick = currentTick
            currentTick = t

            val derived: Vector[TimedElement[DynamoDBRequest]] =
              if prevTick < 0L then
                // First tick: no prior window to compute derived requests from.
                Vector.empty
              else
                // For each derived flow, sample a count and either emit immediately (lag=1)
                // or enqueue for a future tick.
                val immediateBuilder = Vector.newBuilder[TimedElement[DynamoDBRequest]]
                for flow <- flows do
                  val emitTick = prevTick + flow.lagTicks
                  val batch    = deriveRequests(flow, prevTick, emitTick)
                  if batch.nonEmpty then
                    if emitTick <= t then
                      // lag=1 (emitTick == t) or even lag <= elapsed time: emit now into this tick.
                      immediateBuilder ++= batch
                    else
                      // lag > 1: park in delay queue.
                      val q = delayQueues.getOrElseUpdate(emitTick, mutable.Queue.empty)
                      q ++= batch
                immediateBuilder.result()

            // Reset counts for the new tick window.
            outcomeCounts.clear()

            // Drain any queued batches whose target tick has now arrived.
            val drained = drainQueues(t)

            // Output order: Tick first, then immediate derived requests, then drained.
            (tick: TimedElement[DynamoDBRequest]) +: (derived ++ drained)

          case resp: DynamoDBResponse =>
            // Accumulate outcome count; emit nothing.
            resp.flowId.foreach { fid =>
              val filter: OutcomeFilter = resp match
                case _: ThrottledResponse => OutcomeFilter.Throttled
                case _                    => OutcomeFilter.Success
              flows.foreach { f =>
                if f.sourceFlowId == fid && f.outcome == filter then
                  val key = (fid, filter)
                  outcomeCounts(key) = outcomeCounts.getOrElse(key, 0) + 1
              }
            }
            Vector.empty

          case ctrl: TimedControlEvent =>
            // Other TimedControlEvent (EndOfTime, etc.): pass through.
            // TimedControlEvent <: TimedElement[DynamoDBRequest] by the union-type definition.
            Vector(ctrl: TimedElement[DynamoDBRequest])
    }

  /**
   * Resolves the `FlowDefinition.FollowOn` and `FlowDefinition.Retry` entries in `workload`
   * into `ResolvedDerivedFlow` instances ready for use by `FollowOnTransformerStage`.
   *
   * For `FollowOn`: straightforward projection of the ADT fields.
   * For `Retry`:    the request shape is resolved by walking the source-flow chain until
   *                 an `Independent` or `FollowOn` is reached.  Retry-of-Retry chaining is
   *                 supported, enabling explicit multi-attempt client-retry simulation
   *                 (e.g., AWS SDK exponential backoff modelled as three chained Retry
   *                 flows with lagTicks=1,2,4).  Cycles in the source chain are detected
   *                 and rejected.
   *
   * @param workload     The workload whose derived flows are being resolved.
   * @param allWorkloads All known workloads keyed by their `usecase` name, used to resolve
   *                     cross-workload source references.
   */
  def resolveFlows(
    workload:     WorkloadDefinition,
    allWorkloads: Map[String, WorkloadDefinition]
  ): Vector[ResolvedDerivedFlow] =
    workload.derivedFlows.map {
      case FlowDefinition.FollowOn(id, sourceId, sourceFlowId, outcome, proportion, lagTicks, shape) =>
        ResolvedDerivedFlow(id, sourceFlowId, outcome, proportion, lagTicks, shape, workload.usecase)

      case FlowDefinition.Retry(id, sourceId, sourceFlowId, proportion, lagTicks) =>
        val shape = resolveSourceShape(sourceId, sourceFlowId, id, allWorkloads, Set.empty)
        ResolvedDerivedFlow(id, sourceFlowId, OutcomeFilter.Throttled, proportion, lagTicks, shape, workload.usecase)

      case f =>
        // Independent flows are excluded by derivedFlows; this case should not be reached.
        throw IllegalStateException(s"Unexpected FlowDefinition in derivedFlows: $f")
    }

  /** Walks the source chain of a Retry to find the underlying `RequestShape`.  Recurses
   *  through nested Retry references; terminates on Independent or FollowOn.  Tracks
   *  `(workloadId, flowId)` pairs already visited to reject cyclic source chains. */
  private def resolveSourceShape(
    workloadId:   String,
    flowId:       String,
    retryId:      String,
    allWorkloads: Map[String, WorkloadDefinition],
    visited:      Set[(String, String)]
  ): RequestShape =
    if visited.contains((workloadId, flowId)) then
      throw IllegalArgumentException(
        s"Retry flow '$retryId' has a cyclic source chain — '$workloadId.$flowId' is referenced twice"
      )
    val sourceWorkload = allWorkloads.getOrElse(
      workloadId,
      throw IllegalArgumentException(
        s"Retry flow '$retryId' references unknown source workload '$workloadId'"
      )
    )
    val sourceFlow = sourceWorkload.flows.find(_.id == flowId).getOrElse(
      throw IllegalArgumentException(
        s"Retry flow '$retryId' references unknown source flow '$flowId' in workload '$workloadId'"
      )
    )
    sourceFlow match
      case FlowDefinition.Independent(_, paced)           => paced.factory
      case FlowDefinition.FollowOn(_, _, _, _, _, _, sh)  => sh
      case FlowDefinition.Retry(_, sId, sFid, _, _)       =>
        resolveSourceShape(sId, sFid, retryId, allWorkloads, visited + ((workloadId, flowId)))
