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
import stochastacy.workload.{WorkloadDefinition, WorkloadRequestStream}

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

/**
 * Suite 2 — alerts table in open-loop (no WorkloadGraph, no feedback).
 *
 * Uses only the independent flows (a1-poll, a3-write); Retry and FollowOn are stripped.
 * Requests flow straight into the table; responses and consumption events flow straight
 * into Sink.seq.  There is no cycle of any kind.
 *
 * If this suite passes but Suite 3 or 4 fails/hangs, the alerts table component itself
 * is not the source of the deadlock.  If this suite hangs, the table has an internal
 * back-pressure bug unrelated to the feedback loop.
 */
class OpenLoopAlertsTableSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll:

  given ActorSystem     = ActorSystem("open-loop-alerts-test")
  given Materializer    = Materializer.matFromSystem
  given ExecutionContext = summon[ActorSystem].dispatcher

  override protected def afterAll(): Unit =
    Await.result(summon[ActorSystem].terminate(), 10.seconds)
    super.afterAll()

  private val SimTicks      = 30L
  private val config        = EasScenarioConfig(simulationTicks = SimTicks, burstMultiplier = 2.0)
  private val alertsSampler = EasAlertsSampler(config.alertsConfig, RandomSource.KISS.create(99L))
  private val tableConfig   = EasTableConfigs.alertsTableConfig(config.alertsConfig, alertsSampler)

  // Strip Retry and FollowOn, preserving original flow IDs (a1-poll, a3-write).
  // WorkloadDefinition.ofIndependent must NOT be used here — it replaces IDs with
  // synthetic names ("flow-0", "flow-1", …) which breaks flowId assertions.
  private val indepWorkload = WorkloadDefinition(
    tableName = "alerts",
    usecase   = "alerts",
    flows     = config.toAlertsWorkload.independentFlows
  )

  "OpenLoop alerts table" should {

    "terminate and produce response events" in {
      val workloadRng = RandomSource.KISS.create(42L)

      val responsesSink   = Sink.seq[TimedElement[DynamoDBResponse]]
      val consumptionSink = Sink.seq[TimedElement[DynamoDbConsumptionEvent]]

      val (responsesF, consumptionF) = RunnableGraph.fromGraph(
        GraphDSL.createGraph(responsesSink, consumptionSink)((r, c) => (r, c)) { implicit b =>
          (respSink, consSink) =>
            import GraphDSL.Implicits.*
            val src   = b.add(Source.fromIterator(() =>
              WorkloadRequestStream(indepWorkload, workloadRng, SimTicks)
            ))
            val table = b.add(DynamoDbTable.componentOf(tableConfig))
            src.out    ~> table.in
            table.out0 ~> respSink
            table.out1 ~> consSink
            table.out2 ~> b.add(Sink.ignore)
            ClosedShape
        }
      ).run()

      val responses   = Await.result(responsesF,   10.seconds)
      val consumption = Await.result(consumptionF, 10.seconds)

      // DynamoDBResponse events (not control events) must be present
      val actualResponses = responses.collect { case r: DynamoDBResponse => r }
      actualResponses should not be empty

      // At minimum, A1 queries should have consumed read capacity
      val actualConsumption = consumption.collect { case e: DynamoDbConsumptionEvent => e }
      actualConsumption should not be empty

      val readEvents = consumption.collect {
        case e: DynamoDbConsumptionEvent.ReadCapacityConsumed => e
      }
      readEvents should not be empty
    }

    "produce QueryResponse events for a1-poll requests" in {
      val workloadRng = RandomSource.KISS.create(55L)

      val responsesSink = Sink.seq[TimedElement[DynamoDBResponse]]

      val responsesF = RunnableGraph.fromGraph(
        GraphDSL.createGraph(responsesSink) { implicit b =>
          respSink =>
            import GraphDSL.Implicits.*
            val src   = b.add(Source.fromIterator(() =>
              WorkloadRequestStream(indepWorkload, workloadRng, SimTicks)
            ))
            val table = b.add(DynamoDbTable.componentOf(tableConfig))
            src.out    ~> table.in
            table.out0 ~> respSink
            table.out1 ~> b.add(Sink.ignore)
            table.out2 ~> b.add(Sink.ignore)
            ClosedShape
        }
      ).run()

      val responses = Await.result(responsesF, 10.seconds)
      val queries   = responses.collect { case r: QueryResponse => r }
      queries should not be empty
      queries.foreach { q =>
        q.flowId shouldBe defined
        q.flowId.get shouldBe "a1-poll"
      }
    }

    "accumulate non-zero total read capacity units" in {
      val workloadRng = RandomSource.KISS.create(33L)

      val consumptionSink = Sink.fold[BigDecimal, TimedElement[DynamoDbConsumptionEvent]](BigDecimal(0)) {
        (acc, elem) => elem match
          case e: DynamoDbConsumptionEvent.ReadCapacityConsumed => acc + e.units
          case _ => acc
      }

      val totalRcuF = RunnableGraph.fromGraph(
        GraphDSL.createGraph(consumptionSink) { implicit b =>
          consSink =>
            import GraphDSL.Implicits.*
            val src   = b.add(Source.fromIterator(() =>
              WorkloadRequestStream(indepWorkload, workloadRng, SimTicks)
            ))
            val table = b.add(DynamoDbTable.componentOf(tableConfig))
            src.out    ~> table.in
            table.out0 ~> b.add(Sink.ignore)
            table.out1 ~> consSink
            table.out2 ~> b.add(Sink.ignore)
            ClosedShape
        }
      ).run()

      val totalRcu = Await.result(totalRcuF, 10.seconds)
      totalRcu should be > BigDecimal(0)
    }
  }
