package stochastacy.aws.examples.demo

import scala.concurrent.{ExecutionContext, Future}

import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, Materializer}
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}

import stochastacy.aws.dynamodb.{DynamoDbConsumption, DynamoDbRequest, DynamoDbResponse, DynamoDbTable, SystemErrorResponse}
import stochastacy.core.component.Interface
import stochastacy.core.component.gate.ChaosGate
import stochastacy.core.component.Timed
import stochastacy.core.run.SeedSequence
import stochastacy.core.stream.TickFraming
import stochastacy.sim.TimedElement

/**
 * Runs one trial of a [[SingleTableScenario]]: generate the scenario's workload, drive it through a
 * `DynamoDbTable` configured from the scenario, and fold the consumption plane into a [[TrialResult]]
 * (summary + time series). Generic over the domain — the scenario supplies the table state, behavior,
 * indexes, latency, pricing, and arrivals.
 */
final class SingleTableTrialRunner()(using ActorSystem, Materializer, ExecutionContext):

  def runTrial(scenario: SingleTableScenario, trialId: Int, seed: Long): Future[TrialResult] =
    // v2 generates arrivals eagerly, so the workload, table, and gate draw from independent derived rngs.
    // `derive(seed, 3)` shares its first two elements with the old `derive(seed, 2)`, so the workload and
    // table seeds are unchanged; the gate seed is used only when a system-error gate is actually attached.
    val Vector(workloadSeed, tableSeed, gateSeed) = SeedSequence.derive(seed, 3): @unchecked

    val arrivals = scenario.arrivals(RandomSource.KISS.create(workloadSeed))
    val framed   = TickFraming.frame(arrivals.iterator, scenario.simulationTicks).toVector

    val tableConfig = DynamoDbTable.Config(
      initialState           = scenario.initialTableState,
      behavior               = scenario.behavior,
      latency                = scenario.latency,
      globalSecondaryIndexes = scenario.globalSecondaryIndexes,
      localSecondaryIndexes  = scenario.localSecondaryIndexes
    )

    // A load-independent system-error gate on the table's inlet: rejected requests never reach the table,
    // so they consume no capacity and mutate no state. Attached only when the rate is positive, so a
    // scenario with no error rate keeps exactly the unwrapped graph and RNG stream.
    val tableGraph = DynamoDbTable.componentOf(tableConfig, RandomSource.KISS.create(tableSeed))
    val tableComponent =
      if scenario.systemErrorRate > 0.0 then
        Interface.wrap(
          tableGraph,
          ChaosGate.constant[DynamoDbRequest, DynamoDbResponse](scenario.systemErrorRate, SystemErrorResponse),
          RandomSource.KISS.create(gateSeed))
      else tableGraph

    val graph = RunnableGraph.fromGraph(
      GraphDSL.createGraph(Sink.seq[TimedElement[Timed[DynamoDbConsumption]]]) { implicit b => consSink =>
        import GraphDSL.Implicits.*
        val table = b.add(tableComponent)
        b.add(Source(framed)) ~> table.in
        table.out0 ~> b.add(Sink.ignore)
        table.out1 ~> consSink.in
        ClosedShape
      }
    )

    graph.run().map { consumption =>
      val (summary, series) = TrialAccounting.account(consumption, scenario.initialStorageBytesAllTargets, scenario.rates)
      TrialResult(trialId, series, summary)
    }
