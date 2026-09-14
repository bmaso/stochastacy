package stochastacy.demo

import java.nio.file.Path
import java.sql.{Connection, DriverManager}

import scala.io.Source
import scala.util.Try

import org.json4s.*
import org.json4s.jackson.JsonMethods.parse

/**
 * A batch of staged demo records — one row in `stochastacy_demo.demo_batches`, the parent of the demo_records
 * a `generate` run produced. `sourceJsonlPath` records where the JSONL came from (informational).
 */
final case class BatchMetadata(
  batchId:         String,
  scenarioId:      String,
  trialCount:      Int,
  parallelism:     Int,
  simulationTicks: Long,
  baseSeed:        Long,
  readConsistency: String,
  tableName:       String,
  sourceJsonlPath: Option[String]
)

/** One parsed JSONL record, shaped to the `demo_records` columns (the nullable ones as `Option`). */
final case class StagedDemoRecord(
  recordType:        String,
  scenarioId:        String,
  trialId:           Option[Int],
  tick:              Option[Long],
  windowSizeSeconds: Option[Int],
  windowStartTick:   Option[Long],
  metric:            String,
  statistic:         Option[String],
  value:             BigDecimal
)

/**
 * The generic JDBC staging layer for the demo pipeline: load the Postgres schema and stream a demo's JSONL
 * (any of the six `DemoExportRecord` shapes) into `stochastacy_demo.demo_batches` / `demo_records`. It is
 * **metric-agnostic** — `metric` is a free string and the window/trial/statistic columns are nullable — so it
 * stages any demo's export without per-demo code, and the Grafana dashboards read it through the record-type
 * views. Shared by every v2 demo's bridge (`generate → stage → view`).
 */
object DemoPostgresStaging:
  private given Formats = DefaultFormats
  private val JdbcFlushSize = 1000
  private val SchemaResource = "stochastacy/demo/postgres/001-schema.sql"

  /** Run the schema DDL (idempotent — `create ... if not exists` / `create or replace`). */
  def loadSchema(connection: Connection): Unit =
    val schemaSql = Source.fromResource(SchemaResource).mkString
    schemaSql.split(";").map(_.trim).filter(_.nonEmpty).foreach { statement =>
      val stmt = connection.createStatement()
      try stmt.execute(statement) finally stmt.close()
    }

  /** Stage `inputPath`'s JSONL under a new batch, in one transaction. Returns the record count; throws (and
   *  rolls back) on any error, including an empty input or a scenarioId mismatch. */
  def stage(inputPath: Path, metadata: BatchMetadata, dbUrl: String, dbUser: String, dbPassword: String): Int =
    val connection = DriverManager.getConnection(dbUrl, dbUser, dbPassword)
    try
      connection.setAutoCommit(false)
      insertBatch(connection, metadata)
      val count = insertRecordsStreaming(connection, metadata.batchId, metadata.scenarioId, inputPath)
      require(count > 0, "JSONL input must not be empty")
      connection.commit()
      count
    catch
      case t: Throwable => Try(connection.rollback()); throw t
    finally
      connection.close()

  /** Parse JSONL text into records (for tests / validation), requiring a single scenarioId. */
  def parseJsonl(jsonl: String, expectedScenarioId: String): Vector[StagedDemoRecord] =
    val lines = jsonl.linesIterator.map(_.trim).filter(_.nonEmpty).toVector
    require(lines.nonEmpty, "JSONL input must not be empty")
    val records = lines.map(parseRecord)
    require(records.forall(_.scenarioId == expectedScenarioId),
      s"JSONL records must all have scenarioId = $expectedScenarioId")
    records

  private def insertBatch(connection: Connection, metadata: BatchMetadata): Unit =
    val sql =
      """insert into stochastacy_demo.demo_batches
        |(batch_id, scenario_id, trial_count, parallelism, simulation_ticks, base_seed, read_consistency, table_name, source_jsonl_path)
        |values (?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin
    val stmt = connection.prepareStatement(sql)
    try
      stmt.setString(1, metadata.batchId)
      stmt.setString(2, metadata.scenarioId)
      stmt.setInt(3, metadata.trialCount)
      stmt.setInt(4, metadata.parallelism)
      stmt.setLong(5, metadata.simulationTicks)
      stmt.setLong(6, metadata.baseSeed)
      stmt.setString(7, metadata.readConsistency)
      stmt.setString(8, metadata.tableName)
      stmt.setString(9, metadata.sourceJsonlPath.orNull)
      stmt.executeUpdate()
    finally stmt.close()

  private def insertRecordsStreaming(connection: Connection, batchId: String, expectedScenarioId: String, inputPath: Path): Int =
    val sql =
      """insert into stochastacy_demo.demo_records
        |(batch_id, record_type, scenario_id, trial_id, tick, window_size_seconds, window_start_tick, metric, statistic, "value")
        |values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin
    val stmt = connection.prepareStatement(sql)
    var count = 0
    try
      val fileSource = Source.fromFile(inputPath.toFile, "UTF-8")
      try
        for line <- fileSource.getLines() do
          val trimmed = line.trim
          if trimmed.nonEmpty then
            val record = parseRecord(trimmed)
            require(record.scenarioId == expectedScenarioId,
              s"JSONL record has scenarioId '${record.scenarioId}', expected '$expectedScenarioId'")
            stmt.setString(1, batchId)
            stmt.setString(2, record.recordType)
            stmt.setString(3, record.scenarioId)
            stmt.setObject(4, record.trialId.map(Int.box).orNull)
            stmt.setObject(5, record.tick.map(Long.box).orNull)
            stmt.setObject(6, record.windowSizeSeconds.map(Int.box).orNull)
            stmt.setObject(7, record.windowStartTick.map(Long.box).orNull)
            stmt.setString(8, record.metric)
            stmt.setString(9, record.statistic.orNull)
            stmt.setBigDecimal(10, record.value.bigDecimal)
            stmt.addBatch()
            count += 1
            if count % JdbcFlushSize == 0 then stmt.executeBatch()
        if count % JdbcFlushSize != 0 then stmt.executeBatch()
      finally fileSource.close()
      count
    finally stmt.close()

  private def parseRecord(line: String): StagedDemoRecord =
    val json = parse(line)
    StagedDemoRecord(
      recordType        = (json \ "recordType").extract[String],
      scenarioId        = (json \ "scenarioId").extract[String],
      trialId           = (json \ "trialId").extractOpt[Int],
      tick              = (json \ "tick").extractOpt[Long],
      windowSizeSeconds = (json \ "windowSizeSeconds").extractOpt[Int],
      windowStartTick   = (json \ "windowStartTick").extractOpt[Long],
      metric            = (json \ "metric").extract[String],
      statistic         = (json \ "statistic").extractOpt[String],
      value             = (json \ "value").extract[BigDecimal]
    )
