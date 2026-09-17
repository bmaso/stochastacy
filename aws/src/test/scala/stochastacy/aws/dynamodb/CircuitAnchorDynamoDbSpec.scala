package stochastacy.aws.dynamodb

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*

import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.ClosedShape
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.aws.examples.thermostatfleet.ThermostatConfig
import stochastacy.core.component.{ComponentResult, Timed}
import stochastacy.core.component.circuit.{CircuitPlan, CircuitPlane, CircuitResidue, CircuitResult, CircuitStage, ErasedNode, Port, Route, RouteSource, RouteTarget}
import stochastacy.core.stream.TickFraming
import stochastacy.sim.TimedElement

/**
 * The circuit anchor invariant on a **real loopback sampler**: a one-node circuit hosting `DynamoDbTableSampler` is
 * output-identical to `DynamoDbTable.componentOf` (the loopback stage with its feedback inlet tied off) — the same
 * response and consumption element sequences, the same final `TableState`, and the same response / consumption residue.
 * The table's taps have no route in the circuit, so they are counted as unrouted; `componentOf` discards them.
 *
 * Two short thermostat legs: the single-region default (3 GSIs + 1 LSI of mixed projections) and the auto-scaling
 * telemetry table (heavy `onTick` use — the auto-scaler plus a `ProvisionedCapacitySnapshot` boundary fact every tick).
 * Thermostat workloads are sorted within each tick, so the circuit's time-ordered dispatch matches arrival order.
 */
class CircuitAnchorDynamoDbSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("CircuitAnchorDynamoDbSpec")
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  private type Resp = TimedElement[Timed[DynamoDbResponse]]
  private type Cons = TimedElement[Timed[DynamoDbConsumption]]

  private final case class Leg[M](mat: M, responses: Seq[Resp], consumption: Seq[Cons])

  private def drive[M](component: org.apache.pekko.stream.Graph[
    org.apache.pekko.stream.FanOutShape2[TimedElement[Timed[DynamoDbRequest]], Resp, Cons], Future[M]],
    input: Vector[TimedElement[Timed[DynamoDbRequest]]]): Leg[M] =
    val (m, r, c) = RunnableGraph.fromGraph(
      GraphDSL.createGraph(component, Sink.seq[Resp], Sink.seq[Cons])((_, _, _)) { implicit b => (table, respSink, consSink) =>
        import GraphDSL.Implicits.*
        b.add(Source(input)) ~> table.in
        table.out0 ~> respSink.in
        table.out1 ~> consSink.in
        ClosedShape
      }
    ).run()
    Leg(Await.result(m, 60.seconds), Await.result(r, 60.seconds), Await.result(c, 60.seconds))

  /** The table config exactly as `TableLegRunner` builds it from a scenario's `TableSpec`. */
  private def tableConfigOf(scenario: ThermostatConfig): DynamoDbTable.Config =
    val spec = scenario.tableSpec
    DynamoDbTable.Config(
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

  private def assertAnchored(label: String, scenario: ThermostatConfig): Unit =
    val config = tableConfigOf(scenario)
    val input  = TickFraming.frame(scenario.tableSpec.arrivals(RandomSource.KISS.create(11L)).iterator, scenario.simulationTicks).toVector

    val viaStage: Leg[ComponentResult[TableState]] =
      drive(DynamoDbTable.componentOf(config, RandomSource.KISS.create(2L)), input)

    val plan = CircuitPlan(
      Vector(ErasedNode.of(label, new DynamoDbTable.DynamoDbTableSampler(config), RandomSource.KISS.create(2L))),
      Map(
        RouteSource.CircuitInput                           -> Vector(Route(RouteTarget.NodePort(0, Port.In))),
        RouteSource.NodePlane(0, CircuitPlane.Out)         -> Vector(Route(RouteTarget.ForwardOutlet)),
        RouteSource.NodePlane(0, CircuitPlane.Consumption) -> Vector(Route(RouteTarget.ConsumptionOutlet))
      )
    )
    val viaCircuit: Leg[CircuitResult] =
      drive(CircuitStage.componentOf[DynamoDbRequest, DynamoDbResponse, DynamoDbConsumption](plan), input)

    val requests = input.count { case _: Timed[?] => true; case _ => false }
    withClue(s"$label — workload is non-trivial: ")(requests should be > 100)
    withClue(s"$label — responses: ")(viaCircuit.responses shouldBe viaStage.responses)
    withClue(s"$label — consumption: ")(viaCircuit.consumption shouldBe viaStage.consumption)
    withClue(s"$label — final TableState: ")(viaCircuit.mat.nodeStates shouldBe Vector(viaStage.mat.finalState))
    withClue(s"$label — residue: ")(viaCircuit.mat.residue shouldBe
      CircuitResidue(0L, viaStage.mat.residue.responses, viaStage.mat.residue.consumptions))
    // The table taps every admitted write; nothing is routed from its tap plane, so taps are the only unrouted plane.
    viaCircuit.mat.unrouted.map(_.plane).toSet should (be(Set(CircuitPlane.Taps)) or be(Set.empty))
    viaCircuit.mat.unroutedInputs shouldBe 0L

  "A one-node circuit hosting DynamoDbTableSampler" should {
    "match DynamoDbTable.componentOf on the single-region thermostat table (GSIs + LSI)" in {
      assertAnchored("single-region",
        ThermostatConfig.singleRegionDefault.copy(simulationTicks = 60L, initialDeviceCount = 500L))
    }

    "match DynamoDbTable.componentOf on the auto-scaling telemetry table (onTick-heavy)" in {
      assertAnchored("autoscaling",
        ThermostatConfig.autoScalingDefault.copy(simulationTicks = 60L, initialDeviceCount = 500L))
    }
  }
