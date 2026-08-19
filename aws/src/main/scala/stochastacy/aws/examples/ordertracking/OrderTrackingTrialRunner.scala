package stochastacy.aws.examples.ordertracking

import scala.concurrent.{ExecutionContext, Future}

import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, Materializer}
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}

import stochastacy.aws.dynamodb.{DynamoDbConsumption, DynamoDbTable}
import stochastacy.core.component.Timed
import stochastacy.core.run.SeedSequence
import stochastacy.core.sampler.{LogNormalSampler, StatelessSampler}
import stochastacy.core.stream.TickFraming
import stochastacy.sim.TimedElement

/**
 * Runs one Order-Tracking trial: generate the workload, drive it through the table, and fold the
 * consumption plane into a [[OrderTrackingTrialResult]] (summary + time series).
 *
 * The per-op `latency` affects only response timing (which this runner discards) — not any consumption
 * fact or total — so its default is an unremarkable small log-normal.
 */
final class OrderTrackingTrialRunner(
  rates:   Rates                    = OnDemandPricing.phase1Default,
  latency: StatelessSampler[Double] = LogNormalSampler.constant(math.log(0.05), 0.5)
)(using ActorSystem, Materializer, ExecutionContext):

  def runTrial(config: OrderTrackingConfig, trialId: Int, seed: Long): Future[OrderTrackingTrialResult] =
    // v2 generates arrivals eagerly, so the workload and table draw from independent derived rngs.
    val Vector(workloadSeed, tableSeed) = SeedSequence.derive(seed, 2): @unchecked

    val arrivals = OrderTrackingWorkload.arrivals(config, RandomSource.KISS.create(workloadSeed))
    val framed   = TickFraming.frame(arrivals.iterator, config.simulationTicks).toVector

    val tableConfig = DynamoDbTable.Config(
      initialState = config.initialTableState,
      behavior     = new OrderTrackingBehavior(config),
      latency      = latency
    )

    val graph = RunnableGraph.fromGraph(
      GraphDSL.createGraph(Sink.seq[TimedElement[Timed[DynamoDbConsumption]]]) { implicit b => consSink =>
        import GraphDSL.Implicits.*
        val table = b.add(DynamoDbTable.componentOf(tableConfig, RandomSource.KISS.create(tableSeed)))
        b.add(Source(framed)) ~> table.in
        table.out0 ~> b.add(Sink.ignore)
        table.out1 ~> consSink.in
        ClosedShape
      }
    )

    graph.run().map { consumption =>
      val initialStorageBytes = config.initialItemCount * config.initialAverageItemBytes
      val (summary, series)   = TrialAccounting.account(consumption, initialStorageBytes, rates)
      OrderTrackingTrialResult(trialId, series, summary)
    }
