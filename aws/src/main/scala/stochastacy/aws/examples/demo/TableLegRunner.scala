package stochastacy.aws.examples.demo

import scala.concurrent.{ExecutionContext, Future}

import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, Materializer}
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}

import stochastacy.aws.dynamodb.{DynamoDbConsumption, DynamoDbRequest, DynamoDbResponse, DynamoDbTable, SystemErrorResponse}
import stochastacy.core.component.{Interface, Timed}
import stochastacy.core.component.gate.ChaosGate
import stochastacy.core.stream.TickFraming
import stochastacy.sim.TimedElement

/**
 * Runs **one table's leg** of a trial: generate the table's workload, drive it through a `DynamoDbTable`
 * (optionally behind a system-error `ChaosGate`), and fold the consumption plane into a [[TrialResult]].
 * The shared unit behind both [[SingleTableTrialRunner]] and [[MultiTableTrialRunner]] — each supplies the
 * per-leg seeds; this owns the leg mechanics so the two runners never diverge.
 *
 * The returned `TrialResult` carries `trialId = 0`; the caller stamps the real trial id.
 */
object TableLegRunner:

  def run(spec: TableSpec, simulationTicks: Long, workloadSeed: Long, tableSeed: Long, gateSeed: Long)(using
    ActorSystem, Materializer, ExecutionContext
  ): Future[TrialResult] =
    val arrivals = spec.arrivals(RandomSource.KISS.create(workloadSeed))
    val framed   = TickFraming.frame(arrivals.iterator, simulationTicks).toVector

    val tableConfig = DynamoDbTable.Config(
      initialState            = spec.initialTableState,
      behavior                = spec.behavior,
      latency                 = spec.latency,
      globalSecondaryIndexes  = spec.globalSecondaryIndexes,
      localSecondaryIndexes   = spec.localSecondaryIndexes,
      billingMode             = spec.billingMode,
      reconfigurationSchedule = spec.reconfigurationSchedule,
      ttlPeriodTicks          = spec.ttlPeriodTicks,
      burstWindowTicks        = spec.burstWindowTicks,
      autoScalingPolicy       = spec.autoScalingPolicy
    )

    // A load-independent system-error gate on the table's inlet: rejected requests never reach the table,
    // so they consume no capacity and mutate no state. Attached only when the rate is positive, so a table
    // with no error rate keeps exactly the unwrapped graph and RNG stream.
    val tableGraph = DynamoDbTable.componentOf(tableConfig, RandomSource.KISS.create(tableSeed))
    val tableComponent =
      if spec.systemErrorRate > 0.0 then
        Interface.wrap(
          tableGraph,
          ChaosGate.constant[DynamoDbRequest, DynamoDbResponse](spec.systemErrorRate, SystemErrorResponse),
          RandomSource.KISS.create(gateSeed))
      else tableGraph

    // Fold the consumption plane incrementally as it flows, so a leg never holds its raw facts —
    // only the running per-tick accounting (bounded by ticks × metrics).
    val accountingSink =
      Sink.fold[TrialAccountingState, TimedElement[Timed[DynamoDbConsumption]]](
        new TrialAccountingState(
          spec.initialStorageBytesAllTargets,
          spec.rates,
          spec.billingMode,
          spec.globalSecondaryIndexes.map(_.indexName),
          spec.reconfigurationSchedule,
          spec.pointInTimeRecoveryEnabled
        )
      ) { (state, element) => state.update(element); state }

    val graph = RunnableGraph.fromGraph(
      GraphDSL.createGraph(accountingSink) { implicit b => accSink =>
        import GraphDSL.Implicits.*
        val table = b.add(tableComponent)
        b.add(Source(framed)) ~> table.in
        table.out0 ~> b.add(Sink.ignore)
        table.out1 ~> accSink.in
        ClosedShape
      }
    )

    graph.run().map { state =>
      val (summary, series) = state.result()
      TrialResult(trialId = 0, timeSeries = series, summary = summary)
    }
