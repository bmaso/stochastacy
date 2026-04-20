package stochastacy.examples.ordertracking

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.Files
import java.sql.DriverManager
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

class OrderTrackingPostgresBridgeSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  given ActorSystem = ActorSystem("order-tracking-postgres-bridge-test")
  given Materializer = Materializer.matFromSystem
  given ExecutionContext = summon[ActorSystem].dispatcher

  override protected def afterAll(): Unit =
    Await.result(summon[ActorSystem].terminate(), 10.seconds)
    super.afterAll()

  "OrderTrackingPostgresBridge" should {
    "stage generated JSONL into a fresh database and expose raw and windowed record families through views" in {
      val tempFile = Files.createTempFile("order-tracking-stage-", ".jsonl")
      val options = OrderTrackingPhase2DemoOptions(
        outputPath = Some(tempFile),
        trialCount = 2,
        parallelism = 1,
        simulationTicks = 4L
      )

      val bundle = Await.result(OrderTrackingPhase2DemoRunner.run(options), 20.seconds)
      OrderTrackingPhase2DemoRunner.emit(options, bundle)

      val dbUrl = "jdbc:h2:mem:stage_success;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
      val connection = DriverManager.getConnection(dbUrl, "sa", "")
      try
        OrderTrackingPostgresBridge.loadSchema(connection)
      finally
        connection.close()

      val count = OrderTrackingPostgresBridge.stage(
        inputPath = tempFile,
        metadata = BatchMetadata(
          batchId = "batch-stage-success",
          scenarioId = OrderTrackingScenarioConfig.phase2Default.scenarioId,
          trialCount = 2,
          parallelism = 1,
          simulationTicks = 4L,
          baseSeed = OrderTrackingPhase2DemoRunner.Phase2BaseSeed,
          readConsistency = OrderTrackingScenarioConfig.phase2Default.readConsistency.toString,
          tableName = OrderTrackingScenarioConfig.phase2Default.tableName,
          sourceJsonlPath = Some(tempFile.toString)
        ),
        dbUrl = dbUrl,
        dbUser = "sa",
        dbPassword = ""
      )

      count should be > 0

      val verifyConnection = DriverManager.getConnection(dbUrl, "sa", "")
      try
        val batchCount = scalarLong(verifyConnection, "select count(*) from stochastacy_demo.demo_batches")
        val recordCount = scalarLong(verifyConnection, "select count(*) from stochastacy_demo.demo_records")
        val aggregateTsCount = scalarLong(verifyConnection, "select count(*) from stochastacy_demo.aggregate_time_series")
        val aggregateSummaryCount = scalarLong(verifyConnection, "select count(*) from stochastacy_demo.aggregate_summary")
        val trialTsCount = scalarLong(verifyConnection, "select count(*) from stochastacy_demo.trial_time_series")
        val trialSummaryCount = scalarLong(verifyConnection, "select count(*) from stochastacy_demo.trial_summary")
        val trialWindowTsCount = scalarLong(verifyConnection, "select count(*) from stochastacy_demo.trial_window_time_series")
        val aggregateWindowTsCount = scalarLong(verifyConnection, "select count(*) from stochastacy_demo.aggregate_window_time_series")
        val gsiMetricCount = scalarLong(
          verifyConnection,
          "select count(*) from stochastacy_demo.demo_records where metric like 'GSI:%'"
        )

        batchCount shouldBe 1L
        recordCount shouldBe count.toLong
        aggregateTsCount should be > 0L
        aggregateSummaryCount should be > 0L
        trialTsCount should be > 0L
        trialSummaryCount should be > 0L
        trialWindowTsCount should be > 0L
        aggregateWindowTsCount should be > 0L
        gsiMetricCount should be > 0L
      finally
        verifyConnection.close()
    }

    "reject duplicate batch ids" in {
      val tempFile = Files.createTempFile("order-tracking-duplicate-", ".jsonl")
      val options = OrderTrackingPhase2DemoOptions(
        outputPath = Some(tempFile),
        trialCount = 1,
        parallelism = 1,
        simulationTicks = 3L
      )
      val bundle = Await.result(OrderTrackingPhase2DemoRunner.run(options), 20.seconds)
      OrderTrackingPhase2DemoRunner.emit(options, bundle)

      val dbUrl = "jdbc:h2:mem:duplicate_batch;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
      val connection = DriverManager.getConnection(dbUrl, "sa", "")
      try
        OrderTrackingPostgresBridge.loadSchema(connection)
      finally
        connection.close()

      val metadata = BatchMetadata(
        batchId = "duplicate-batch",
        scenarioId = OrderTrackingScenarioConfig.phase2Default.scenarioId,
        trialCount = 1,
        parallelism = 1,
        simulationTicks = 3L,
        baseSeed = OrderTrackingPhase2DemoRunner.Phase2BaseSeed,
        readConsistency = OrderTrackingScenarioConfig.phase2Default.readConsistency.toString,
        tableName = OrderTrackingScenarioConfig.phase2Default.tableName,
        sourceJsonlPath = Some(tempFile.toString)
      )

      OrderTrackingPostgresBridge.stage(tempFile, metadata, dbUrl, "sa", "")

      an[Throwable] should be thrownBy {
        OrderTrackingPostgresBridge.stage(tempFile, metadata, dbUrl, "sa", "")
      }
    }

    "reject empty or malformed JSONL input" in {
      val emptyThrown = the[IllegalArgumentException] thrownBy {
        OrderTrackingPostgresBridge.parseJsonl("", OrderTrackingScenarioConfig.phase2Default.scenarioId)
      }
      emptyThrown.getMessage should include("must not be empty")

      an[Throwable] should be thrownBy {
        OrderTrackingPostgresBridge.parseJsonl("""{"recordType":"trial-time-series"}""", OrderTrackingScenarioConfig.phase2Default.scenarioId)
      }
    }

    "accept older raw-only JSONL records without window fields" in {
      val records = OrderTrackingPostgresBridge.parseJsonl(
        """{"recordType":"trial-time-series","scenarioId":"order-tracking-phase2","trialId":0,"tick":1,"metric":"ReadCapacityUnits","value":2}""",
        OrderTrackingScenarioConfig.phase2Default.scenarioId
      )

      records should have size 1
      records.head.windowSizeSeconds shouldBe None
      records.head.windowStartTick shouldBe None
    }
  }

  private def scalarLong(connection: java.sql.Connection, sql: String): Long =
    val stmt = connection.createStatement()
    try
      val rs = stmt.executeQuery(sql)
      try
        rs.next()
        rs.getLong(1)
      finally
        rs.close()
    finally
      stmt.close()
