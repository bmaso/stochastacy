package stochastacy.examples.grafana

import java.nio.file.Files
import java.sql.{Connection, DriverManager}

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.aws.examples.ordertracking.OrderTrackingConfig
import stochastacy.demo.{BatchMetadata, DemoPostgresStaging, GrafanaBridge}

/**
 * End-to-end (minus live Grafana): the shared v2 bridge runs the v2 order-tracking runner, adapts it into the
 * generic staging model, writes JSONL, and stages it into an in-memory H2 (PostgreSQL mode). Asserts the record
 * families the dashboards read — including the **windowed** views the v2 AWS exporter never produced and the
 * per-GSI metrics the phase-2 dashboard needs — all populate. The live Grafana `view` step is runbook-verified.
 */
class GrafanaBridgeSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("GrafanaBridgeSpec")
  private given Materializer        = Materializer.matFromSystem
  private given ExecutionContext    = system.dispatcher
  override def afterAll(): Unit = Await.result(system.terminate(), 10.seconds)

  private def scalarLong(c: Connection, sql: String): Long =
    val stmt = c.createStatement()
    try { val rs = stmt.executeQuery(sql); try { rs.next(); rs.getLong(1) } finally rs.close() } finally stmt.close()

  "The shared v2 Grafana bridge" should {
    "generate + stage the indexed order-tracking demo so every dashboard record family (incl. windows + GSI) populates" in {
      val scenario = OrderTrackingConfig.indexedDefault.copy(trialCount = 2, simulationTicks = 6L, parallelism = 1)
      val jsonl    = Files.createTempFile("grafana-bridge-", ".jsonl")
      val count    = Await.result(GrafanaBridge.generateSingleTable(scenario, masterSeed = 1L, output = jsonl, offsetSeconds = 0L), 60.seconds)
      count should be > 0

      val dbUrl = "jdbc:h2:mem:grafana_bridge;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
      val setup = DriverManager.getConnection(dbUrl, "sa", "")
      try DemoPostgresStaging.loadSchema(setup) finally setup.close()

      val staged = DemoPostgresStaging.stage(
        inputPath = jsonl,
        metadata = BatchMetadata(
          batchId = "grafana-bridge-test", scenarioId = scenario.scenarioId, trialCount = 2, parallelism = 1,
          simulationTicks = 6L, baseSeed = 1L, readConsistency = "StronglyConsistent", tableName = "orders",
          sourceJsonlPath = Some(jsonl.toString)),
        dbUrl = dbUrl, dbUser = "sa", dbPassword = "")
      staged shouldBe count

      val verify = DriverManager.getConnection(dbUrl, "sa", "")
      try
        scalarLong(verify, "select count(*) from stochastacy_demo.demo_batches")               shouldBe 1L
        scalarLong(verify, "select count(*) from stochastacy_demo.demo_records")               shouldBe count.toLong
        scalarLong(verify, "select count(*) from stochastacy_demo.trial_time_series")          should be > 0L
        scalarLong(verify, "select count(*) from stochastacy_demo.trial_summary")              should be > 0L
        scalarLong(verify, "select count(*) from stochastacy_demo.aggregate_time_series")      should be > 0L
        scalarLong(verify, "select count(*) from stochastacy_demo.aggregate_summary")          should be > 0L
        // The families the v2 AWS exporter never produced — the dashboards' time panels depend on these:
        scalarLong(verify, "select count(*) from stochastacy_demo.trial_window_time_series")   should be > 0L
        scalarLong(verify, "select count(*) from stochastacy_demo.aggregate_window_time_series") should be > 0L
        // Per-GSI metrics the phase-2 dashboard's GSI panels select on:
        scalarLong(verify, "select count(*) from stochastacy_demo.demo_records where metric like 'GSI:%:ReadCapacityUnits'") should be > 0L
        // The summary metrics the "Central Range" stats read:
        scalarLong(verify, "select count(*) from stochastacy_demo.aggregate_summary where metric = 'TotalEstimatedCost'") should be > 0L
        scalarLong(verify, "select count(*) from stochastacy_demo.aggregate_summary where metric = 'FinalStorageBytes'")  should be > 0L
      finally verify.close()
    }
  }
