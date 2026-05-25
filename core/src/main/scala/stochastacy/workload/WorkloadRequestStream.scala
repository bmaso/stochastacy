package stochastacy.workload

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.simple.RandomSource
import stochastacy.aws.dynamodb.{
  DynamoDBRequest, DeleteItemRequest, GetItemRequest, PutItemRequest,
  QueryRequest, ScanRequest, TransactGetItemsRequest, TransactWriteItemsRequest,
  UpdateItemRequest
}
import stochastacy.sim.{SimTime, TimedControlEvent, TimedElement}

object WorkloadRequestStream:

  /** Produces an `Iterator[TimedElement[DynamoDBRequest]]` driven entirely by
   *  the workload definition. Output structure is identical to
   *  `generateRequestsForRegion`: each tick opens with a `Tick` control event
   *  followed by all requests for that tick, and a final `Tick(simulationTicks + 1)`
   *  flushes the last window.
   *
   *  Two independent RNGs are split from `rng` per shape — one for rate draws,
   *  one for parameter draws — so that changing a param sampler does not affect
   *  rate draws and vice versa. */
  def apply(
    workload:        WorkloadDefinition,
    rng:             UniformRandomProvider,
    simulationTicks: Long
  ): Iterator[TimedElement[DynamoDBRequest]] =

    // Only independent flows have their own rate samplers.
    // Derived flows (follow-on, retry) are handled by FollowOnTransformerStage.
    val independent = workload.independentFlows
    val n           = independent.size
    val rateRngs    = Vector.fill(n)(RandomSource.KISS.create(rng.nextLong()))
    val paramRngs   = Vector.fill(n)(RandomSource.KISS.create(rng.nextLong()))

    (1L to simulationTicks).iterator.flatMap { tick =>
      Iterator.single(TimedControlEvent.Tick(SimTime.of(tick)): TimedElement[DynamoDBRequest]) ++
        (0 until n).iterator.flatMap { i =>
          val defn = independent(i).defn
          val (count, _) = defn.rate.sample(tick, rateRngs(i), ())
          Iterator.fill(count) {
            buildRequest(tick, workload.usecase, independent(i).id, defn.shape, paramRngs(i))
          }
        }
    } ++ Iterator.single(TimedControlEvent.Tick(SimTime.of(simulationTicks + 1L)))

  /** Builds a single tagged request. `flowId` is set on the request so that downstream
   *  response events can be attributed back to the originating flow. */
  private[workload] def buildRequest(
    tick:    Long,
    usecase: String,
    flowId:  String,
    shape:   RequestShape,
    rng:     UniformRandomProvider
  ): TimedElement[DynamoDBRequest] =
    val t   = SimTime.of(tick)
    val fid = Some(flowId)
    shape match
      case RequestShape.GetItem =>
        GetItemRequest(t, usecase, fid)
      case RequestShape.DeleteItem =>
        DeleteItemRequest(t, usecase, fid)
      case RequestShape.PutItem(itemBytesSampler) =>
        PutItemRequest(t, usecase, itemBytesSampler.sample(tick, rng, ())._1, fid)
      case RequestShape.UpdateItem(itemBytesSampler) =>
        UpdateItemRequest(t, usecase, itemBytesSampler.sample(tick, rng, ())._1, fid)
      case RequestShape.Query(target, readConsistency) =>
        QueryRequest(t, usecase, target, readConsistency, flowId = fid)
      case RequestShape.Scan(target, readConsistency) =>
        ScanRequest(t, usecase, target, readConsistency, flowId = fid)
      case RequestShape.TransactWriteItems(perItemSamplers) =>
        TransactWriteItemsRequest(t, usecase, perItemSamplers.map(_.sample(tick, rng, ())._1), fid)
      case RequestShape.TransactGetItems(itemCountSampler) =>
        TransactGetItemsRequest(t, usecase, itemCountSampler.sample(tick, rng, ())._1, fid)
