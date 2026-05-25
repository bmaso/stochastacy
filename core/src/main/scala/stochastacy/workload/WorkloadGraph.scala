package stochastacy.workload

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.{Flow, GraphDSL, MergePreferred, Sink, Source}
import org.apache.pekko.stream.{Graph, Inlet, Outlet, Shape}
import stochastacy.aws.dynamodb.{DynamoDBRequest, DynamoDBResponse}
import stochastacy.sim.TimedElement

import scala.collection.immutable

/**
 * Custom Shape for `WorkloadGraph`. Exposes a single response inlet (fed by the simulator's
 * response outlet) and a single request outlet (fed into the simulator's request inlet).
 *
 * The graph wires together:
 *   - a base `WorkloadRequestStream` source (independent flows only)
 *   - a `FollowOnTransformerStage` that observes responses and injects derived requests
 *   - a `MergePreferred` that gives the base stream priority over derived requests
 */
final class WorkloadGraphShape(
  val responseIn: Inlet[TimedElement[DynamoDBResponse]],
  val requestOut: Outlet[TimedElement[DynamoDBRequest]]
) extends Shape:

  override val inlets:  immutable.Seq[Inlet[?]]  = immutable.Seq(responseIn)
  override val outlets: immutable.Seq[Outlet[?]] = immutable.Seq(requestOut)

  override def deepCopy(): WorkloadGraphShape =
    new WorkloadGraphShape(responseIn.carbonCopy(), requestOut.carbonCopy())

/**
 * Factory that builds the workload feedback graph for a single `WorkloadDefinition`.
 *
 * Topology when `derivedFlows` is non-empty:
 *
 * {{{
 *   Source(WorkloadRequestStream) ───────────────────────────→ MergePreferred.preferred
 *                                                                      ↓
 *   responseIn ──→ FollowOnTransformerStage ──────────────────→ MergePreferred.in(0)
 *                                                                      ↓
 *                                                               requestOut
 * }}}
 *
 * `MergePreferred` gives priority to the base workload stream so that independent-flow
 * requests are not delayed by back-pressure from derived-flow injection.
 *
 * Topology when `derivedFlows` is empty (no follow-on or retry flows):
 *
 * {{{
 *   Source(WorkloadRequestStream) ──────────────────────────→ requestOut
 *   responseIn ──────────────────────────────────────────────→ Sink.ignore
 * }}}
 *
 * In both cases a valid `WorkloadGraphShape` is returned: callers always connect the
 * simulator response stream to `responseIn` regardless of whether derived flows exist.
 *
 * RNG splitting: two child RNGs are derived from the caller-supplied `rng` using
 * `RandomSource.KISS.create(rng.nextLong())`. One seeds the `WorkloadRequestStream`;
 * the other seeds the `FollowOnTransformerStage`.
 */
object WorkloadGraph:

  def apply(
    workload:        WorkloadDefinition,
    allWorkloads:    Map[String, WorkloadDefinition],
    rng:             UniformRandomProvider,
    simulationTicks: Long
  ): Graph[WorkloadGraphShape, NotUsed] =

    // Split RNGs so the stream generator and the transformer are independent.
    val streamRng      = RandomSource.KISS.create(rng.nextLong())
    val transformerRng = RandomSource.KISS.create(rng.nextLong())

    val resolvedDerived = FollowOnTransformerStage.resolveFlows(workload, allWorkloads)

    if resolvedDerived.isEmpty then
      // Simple path: no derived flows. Route responses to Sink.ignore and expose the
      // base workload source directly. Build via GraphDSL so we get a properly materialisable
      // Graph[WorkloadGraphShape, NotUsed].
      GraphDSL.create() { implicit b =>
        import GraphDSL.Implicits.*

        // The response flow shape gives us the inlet we expose in the shape; its outlet
        // is immediately discarded.
        val responseFlow = b.add(Flow[TimedElement[DynamoDBResponse]])
        responseFlow.out ~> Sink.ignore

        val baseSource = b.add(
          Source.fromIterator(() => WorkloadRequestStream(workload, streamRng, simulationTicks))
        )

        WorkloadGraphShape(responseFlow.in, baseSource.out)
      }

    else
      GraphDSL.create() { implicit b =>
        import GraphDSL.Implicits.*

        val baseSource  = b.add(
          Source.fromIterator(() => WorkloadRequestStream(workload, streamRng, simulationTicks))
        )
        val transformer = b.add(
          FollowOnTransformerStage(resolvedDerived, transformerRng)
        )
        // MergePreferred: preferred input = base workload; secondary inputs = transformer output.
        // eagerComplete=false keeps the merge open until all inputs complete.
        val merge = b.add(MergePreferred[TimedElement[DynamoDBRequest]](1, eagerComplete = false))

        // Base workload → preferred inlet of merge.
        baseSource.out ~> merge.preferred

        // Transformer output → secondary inlet 0 of merge.
        transformer.out ~> merge.in(0)

        WorkloadGraphShape(transformer.in, merge.out)
      }

