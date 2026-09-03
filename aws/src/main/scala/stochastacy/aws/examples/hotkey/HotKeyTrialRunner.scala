package stochastacy.aws.examples.hotkey

import scala.concurrent.{ExecutionContext, Future}

import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, Materializer}
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}

import stochastacy.aws.dynamodb.{DynamoDbConsumption, DynamoDbRequest, DynamoDbResponse, DynamoDbTable, PartitionTopology, TableState, ThrottledResponse}
import stochastacy.core.component.Timed
import stochastacy.core.run.SeedSequence
import stochastacy.core.sampler.LogNormalSampler
import stochastacy.core.stream.TickFraming
import stochastacy.sim.{TimedControlEvent, TimedElement, ticks}

/** One tick's admitted/throttled response counts. `admitted = offered − throttled`. */
final case class TickCounts(tick: Long, offered: Long, throttled: Long):
  def admitted: Long = offered - throttled

/** One trial's outcome: per-tick response counts and the final effective partition count (derived base +
 *  accumulated split-for-heat bump), the evidence that split-for-heat grew the topology. */
final case class HotKeyTrialResult(trialId: Int, perTick: Vector[TickCounts], finalPartitionCount: Int):
  def totalOffered:   Long = perTick.iterator.map(_.offered).sum
  def totalThrottled: Long = perTick.iterator.map(_.throttled).sum

/**
 * Runs one trial of a [[HotKeyConfig]] on a **bespoke** graph — not the `SingleTableScenario` harness. It
 * frames the workload, builds the `DynamoDbTable.Config` directly (setting `adaptiveCapacity` +
 * `heatSplitPolicy`), and combines two materialized values: the response plane folded into per-tick
 * offered/throttled counts, and the component's final `TableState` for the effective partition count. The
 * consumption plane is ignored — this demo's metrics are throttling / relief / topology, not cost.
 */
final class HotKeyTrialRunner()(using ActorSystem, Materializer, ExecutionContext):

  def runTrial(config: HotKeyConfig, trialId: Int, seed: Long): Future[HotKeyTrialResult] =
    val Vector(workloadSeed, tableSeed) = SeedSequence.derive(seed, 2): @unchecked

    val arrivals = HotKeyWorkload.arrivals(config, RandomSource.KISS.create(workloadSeed))
    val framed   = TickFraming.frame(arrivals.iterator, config.simulationTicks).toVector

    val tableConfig = DynamoDbTable.Config(
      initialState     = config.initialTableState,
      behavior         = new HotKeyBehavior(config),
      latency          = LogNormalSampler.constant(math.log(0.005), 0.0), // small, constant per-op latency

      billingMode      = config.billingMode,
      adaptiveCapacity = config.adaptiveCapacity,
      heatSplitPolicy  = config.heatSplitPolicy
    )
    val tableComponent = DynamoDbTable.componentOf(tableConfig, RandomSource.KISS.create(tableSeed))

    // Fold the response plane into per-tick (offered, throttled) counts, bucketed by each response's tick.
    val perTickSink =
      Sink.fold[Map[Long, (Long, Long)], TimedElement[Timed[DynamoDbResponse]]](Map.empty) { (acc, element) =>
        element match
          case _: TimedControlEvent => acc
          case t: Timed[DynamoDbResponse] @unchecked =>
            val tick       = t.eventTime.ticks
            val (off, thr) = acc.getOrElse(tick, (0L, 0L))
            acc.updated(tick, (off + 1L, if t.event == ThrottledResponse then thr + 1L else thr))
      }

    val graph = RunnableGraph.fromGraph(
      GraphDSL.createGraph(perTickSink, tableComponent)((countsF, resultF) => (countsF, resultF)) {
        implicit b => (counts, table) =>
          import GraphDSL.Implicits.*
          b.add(Source(framed)) ~> table.in
          table.out0 ~> counts
          table.out1 ~> b.add(Sink.ignore)
          ClosedShape
      }
    )

    val (countsF, resultF) = graph.run()
    for
      counts <- countsF
      result <- resultF
    yield
      val perTick = (1L to config.simulationTicks).map { tick =>
        val (off, thr) = counts.getOrElse(tick, (0L, 0L))
        TickCounts(tick, off, thr)
      }.toVector
      HotKeyTrialResult(trialId, perTick, effectivePartitionCount(config, result.finalState))

  /** The final effective partition count: the derived base grown by the accumulated heat-split bump, capped. */
  private def effectivePartitionCount(config: HotKeyConfig, state: TableState): Int =
    val base = PartitionTopology.derive(config.billingMode.readCapacityUnits, config.billingMode.writeCapacityUnits, state.base.totalItemBytes)
    config.heatSplitPolicy.fold(base)(pol => math.min(base + state.heatSplit.bump, pol.maxPartitionCount))
