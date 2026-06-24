package stochastacy.examples.eas

import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, Materializer}
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.{DynamoDBResponse, QueryResponse}
import stochastacy.aws.dynamodb.table.{DynamoDbConsumptionEvent, DynamoDbTable}
import stochastacy.sim.TimedElement
import stochastacy.workload.{WorkloadDefinition, WorkloadGraph}

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

/**
 * Suite 3 — WorkloadGraph (independent flows only, no Retry/FollowOn) wired to the
 * real alerts table.
 *
 * When resolvedDerived.isEmpty WorkloadGraph internally routes responseIn → Sink.ignore,
 * so the outer connection (table.out0 → wg.responseIn) feeds real responses into a
 * discard sink.  There is no FollowOnTransformerStage and no feedback cycle.
 *
 * If this suite passes but Suite 4 hangs, WorkloadGraph's no-derived-flow path and the
 * real table are both fine; the deadlock lives specifically in the derived-flow feedback
 * cycle.  If this suite hangs, something is wrong in the no-feedback path.
 */
class WorkloadGraphNoFeedbackSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll:

  given ActorSystem     = ActorSystem("workload-graph-no-feedback-test")
  given Materializer    = Materializer.matFromSystem
  given ExecutionContext = summon[ActorSystem].dispatcher

  override protected def afterAll(): Unit =
    Await.result(summon[ActorSystem].terminate(), 10.seconds)
    super.afterAll()

  private val SimTicks      = 30L
  private val config        = EasScenarioConfig(simulationTicks = SimTicks, burstMultiplier = 2.0)
  private val alertsSampler = EasAlertsSampler(config.alertsConfig, RandomSource.KISS.create(99L))
  private val tableConfig   = EasTableConfigs.alertsTableConfig(config.alertsConfig, alertsSampler)

  // Independent-only workload: no Retry, no FollowOn → resolvedDerived.isEmpty.
  // Preserve original flow IDs (a1-poll, a3-write) — do NOT use ofIndependent,
  // which replaces them with synthetic names.
  private val indepWorkload = WorkloadDefinition(
    tableName = "alerts",
    usecase   = "alerts",
    flows     = config.toAlertsWorkload.independentFlows
  )

  "WorkloadGraph with no derived flows and real alerts table" should {

    "terminate and produce consumption events" in {
      val workloadRng = RandomSource.KISS.create(42L)

      val consumptionSink = Sink.seq[TimedElement[DynamoDbConsumptionEvent]]

      val consumptionF = RunnableGraph.fromGraph(
        GraphDSL.createGraph(consumptionSink) { implicit b =>
          consSink =>
            import GraphDSL.Implicits.*
            val wg    = b.add(WorkloadGraph(indepWorkload, Map.empty, workloadRng, SimTicks))
            val table = b.add(DynamoDbTable.componentOf(tableConfig))
            // requestOut → table; table.out0 responses feed back into responseIn
            // which WorkloadGraph routes internally to Sink.ignore (no-derived-flow path)
            wg.requestOut  ~> table.in
            table.out0     ~> wg.responseIn
            table.out1     ~> consSink
            table.out2     ~> b.add(Sink.ignore)
            ClosedShape
        }
      ).run()

      val consumption = Await.result(consumptionF, 10.seconds)
      val readEvents  = consumption.collect { case e: DynamoDbConsumptionEvent.ReadCapacityConsumed => e }
      readEvents should not be empty
    }

    "be deterministic — same seed produces the same total RCU on two runs" in {
      // WorkloadGraph internally derives streamRng from rng.nextLong(), so its output
      // is NOT numerically identical to a WorkloadRequestStream using the same seed
      // directly.  What we CAN assert is reproducibility: two runs with the same seed
      // must produce exactly the same result.
      def totalRcu(seed: Long): BigDecimal =
        val wgRng      = RandomSource.KISS.create(seed)
        val samplerRng = RandomSource.KISS.create(99L)
        val sampler    = EasAlertsSampler(config.alertsConfig, samplerRng)
        val tConfig    = EasTableConfigs.alertsTableConfig(config.alertsConfig, sampler)
        val consSink   = Sink.fold[BigDecimal, TimedElement[DynamoDbConsumptionEvent]](BigDecimal(0)) {
          (acc, e) => e match
            case r: DynamoDbConsumptionEvent.ReadCapacityConsumed => acc + r.units
            case _ => acc
        }
        val f = RunnableGraph.fromGraph(
          GraphDSL.createGraph(consSink) { implicit b =>
            cs =>
              import GraphDSL.Implicits.*
              val wg    = b.add(WorkloadGraph(indepWorkload, Map.empty, wgRng, SimTicks))
              val table = b.add(DynamoDbTable.componentOf(tConfig))
              wg.requestOut ~> table.in
              table.out0    ~> wg.responseIn
              table.out1    ~> cs
              table.out2    ~> b.add(Sink.ignore)
              ClosedShape
          }
        ).run()
        Await.result(f, 10.seconds)

      val firstRun  = totalRcu(77L)
      val secondRun = totalRcu(77L)
      firstRun should be > BigDecimal(0)
      firstRun shouldBe secondRun
    }
  }
