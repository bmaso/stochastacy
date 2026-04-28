package stochastacy.examples.ordertracking

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.json4s.*
import org.json4s.jackson.JsonMethods.parse
import stochastacy.demo.{DemoExportBundle, DemoExportRecord, DemoJsonlExporter, DemoReportBuilder, FutureMultiTrialExecutor, IncrementalMonteCarloAgg, IncrementalWindowedAgg, TimeWindowRollups, TrialExecutionConfig, WindowSizeSeconds}

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.sql.{Connection, DriverManager}
import java.time.format.DateTimeFormatter
import java.time.{ZoneOffset, ZonedDateTime}
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.io.Source
import scala.util.{Failure, Success, Try}

final case class OrderTrackingPhase2DemoOptions(
                                                 outputPath: Option[Path],
                                                 trialCount: Int,
                                                 parallelism: Int,
                                                 simulationTicks: Long
                                               )

final case class BatchMetadata(
                                batchId: String,
                                scenarioId: String,
                                trialCount: Int,
                                parallelism: Int,
                                simulationTicks: Long,
                                baseSeed: Long,
                                readConsistency: String,
                                tableName: String,
                                sourceJsonlPath: Option[String]
                              )

sealed trait OrderTrackingBridgeCommand

object OrderTrackingBridgeCommand:
  final case class Generate(
                             batchId: String,
                             outputPath: Path,
                             trialCount: Int,
                             parallelism: Int,
                             simulationTicks: Long
                           ) extends OrderTrackingBridgeCommand

  final case class Stage(
                          inputPath: Path,
                          metadata: BatchMetadata,
                          dbUrl: String,
                          dbUser: String,
                          dbPassword: String
                        ) extends OrderTrackingBridgeCommand

  final case class View(
                         grafanaBaseUrl: String,
                         batchId: String,
                         scenarioId: String
                       ) extends OrderTrackingBridgeCommand

object OrderTrackingPhase2BridgeCli:
  private val GenerateUsage =
    "usage: OrderTrackingPhase2Bridge generate --output <path> [--batch-id <id>] [--trial-count <int>] [--parallelism <int>] [--simulation-ticks <long>]"
  private val StageUsage =
    "usage: OrderTrackingPhase2Bridge stage --input <path> --batch-id <id> --db-url <jdbc-url> --db-user <user> --db-password <password> --trial-count <int> --parallelism <int> --simulation-ticks <long> [--scenario-id <id>] [--read-consistency <value>] [--table-name <name>]"
  private val ViewUsage =
    "usage: OrderTrackingPhase2Bridge view --batch-id <id> [--scenario-id <id>] [--grafana-base-url <url>]"
  private val TopLevelUsage =
    s"""usage:
       |  $GenerateUsage
       |  $StageUsage
       |  $ViewUsage""".stripMargin

  def parseArgs(
                 args: Seq[String],
                 now: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC)
               ): Either[String, OrderTrackingBridgeCommand] =
    args.toList match
      case "generate" :: tail => parseGenerate(tail, now)
      case "stage" :: tail => parseStage(tail)
      case "view" :: tail => parseView(tail)
      case Nil => Left(TopLevelUsage)
      case subcommand :: _ => Left(s"unknown subcommand: $subcommand\n$TopLevelUsage")

  private def parseGenerate(
                             args: List[String],
                             now: ZonedDateTime
                           ): Either[String, OrderTrackingBridgeCommand.Generate] =
    val defaults = OrderTrackingScenarioConfig.phase2Default

    def loop(
              remaining: List[String],
              outputPath: Option[Path],
              batchId: Option[String],
              trialCount: Option[Int],
              parallelism: Option[Int],
              simulationTicks: Option[Long]
            ): Either[String, OrderTrackingBridgeCommand.Generate] =
      remaining match
        case Nil =>
          outputPath match
            case Some(path) =>
              Right(
                OrderTrackingBridgeCommand.Generate(
                  batchId = batchId.getOrElse(defaultBatchId(now)),
                  outputPath = path,
                  trialCount = trialCount.getOrElse(defaults.trialCount),
                  parallelism = parallelism.getOrElse(defaults.parallelism),
                  simulationTicks = simulationTicks.getOrElse(defaults.simulationTicks)
                )
              )
            case None =>
              Left(s"missing required flag: --output\n$GenerateUsage")

        case "--output" :: value :: tail =>
          if outputPath.nonEmpty then Left(s"duplicate flag: --output\n$GenerateUsage")
          else loop(tail, Some(Path.of(value)), batchId, trialCount, parallelism, simulationTicks)

        case "--batch-id" :: value :: tail =>
          if batchId.nonEmpty then Left(s"duplicate flag: --batch-id\n$GenerateUsage")
          else loop(tail, outputPath, Some(value), trialCount, parallelism, simulationTicks)

        case "--trial-count" :: value :: tail =>
          if trialCount.nonEmpty then Left(s"duplicate flag: --trial-count\n$GenerateUsage")
          else parseIntFlag("--trial-count", value, GenerateUsage).flatMap(parsed =>
            loop(tail, outputPath, batchId, Some(parsed), parallelism, simulationTicks)
          )

        case "--parallelism" :: value :: tail =>
          if parallelism.nonEmpty then Left(s"duplicate flag: --parallelism\n$GenerateUsage")
          else parseIntFlag("--parallelism", value, GenerateUsage).flatMap(parsed =>
            loop(tail, outputPath, batchId, trialCount, Some(parsed), simulationTicks)
          )

        case "--simulation-ticks" :: value :: tail =>
          if simulationTicks.nonEmpty then Left(s"duplicate flag: --simulation-ticks\n$GenerateUsage")
          else parseLongFlag("--simulation-ticks", value, GenerateUsage).flatMap(parsed =>
            loop(tail, outputPath, batchId, trialCount, parallelism, Some(parsed))
          )

        case flag :: Nil if flag.startsWith("--") =>
          Left(s"missing value for flag: $flag\n$GenerateUsage")

        case flag :: _ if flag.startsWith("--") =>
          Left(s"unknown flag: $flag\n$GenerateUsage")

        case value :: _ =>
          Left(s"unexpected argument: $value\n$GenerateUsage")

    loop(args, None, None, None, None, None)

  private def parseStage(
                          args: List[String]
                        ): Either[String, OrderTrackingBridgeCommand.Stage] =
    val defaults = OrderTrackingScenarioConfig.phase2Default

    def loop(
              remaining: List[String],
              inputPath: Option[Path],
              batchId: Option[String],
              dbUrl: Option[String],
              dbUser: Option[String],
              dbPassword: Option[String],
              scenarioId: Option[String],
              trialCount: Option[Int],
              parallelism: Option[Int],
              simulationTicks: Option[Long],
              readConsistency: Option[String],
              tableName: Option[String]
            ): Either[String, OrderTrackingBridgeCommand.Stage] =
      remaining match
        case Nil =>
          for
            path <- inputPath.toRight(s"missing required flag: --input\n$StageUsage")
            id <- batchId.toRight(s"missing required flag: --batch-id\n$StageUsage")
            jdbcUrl <- dbUrl.toRight(s"missing required flag: --db-url\n$StageUsage")
            user <- dbUser.toRight(s"missing required flag: --db-user\n$StageUsage")
            password <- dbPassword.toRight(s"missing required flag: --db-password\n$StageUsage")
            tc <- trialCount.toRight(s"missing required flag: --trial-count\n$StageUsage")
            p <- parallelism.toRight(s"missing required flag: --parallelism\n$StageUsage")
            ticks <- simulationTicks.toRight(s"missing required flag: --simulation-ticks\n$StageUsage")
          yield
            OrderTrackingBridgeCommand.Stage(
              inputPath = path,
              metadata = BatchMetadata(
                batchId = id,
                scenarioId = scenarioId.getOrElse(defaults.scenarioId),
                trialCount = tc,
                parallelism = p,
                simulationTicks = ticks,
                baseSeed = OrderTrackingPhase2DemoRunner.Phase2BaseSeed,
                readConsistency = readConsistency.getOrElse(defaults.readConsistency.toString),
                tableName = tableName.getOrElse(defaults.tableName),
                sourceJsonlPath = Some(path.toString)
              ),
              dbUrl = jdbcUrl,
              dbUser = user,
              dbPassword = password
            )

        case "--input" :: value :: tail =>
          if inputPath.nonEmpty then Left(s"duplicate flag: --input\n$StageUsage")
          else loop(tail, Some(Path.of(value)), batchId, dbUrl, dbUser, dbPassword, scenarioId, trialCount, parallelism, simulationTicks, readConsistency, tableName)

        case "--batch-id" :: value :: tail =>
          if batchId.nonEmpty then Left(s"duplicate flag: --batch-id\n$StageUsage")
          else loop(tail, inputPath, Some(value), dbUrl, dbUser, dbPassword, scenarioId, trialCount, parallelism, simulationTicks, readConsistency, tableName)

        case "--db-url" :: value :: tail =>
          if dbUrl.nonEmpty then Left(s"duplicate flag: --db-url\n$StageUsage")
          else loop(tail, inputPath, batchId, Some(value), dbUser, dbPassword, scenarioId, trialCount, parallelism, simulationTicks, readConsistency, tableName)

        case "--db-user" :: value :: tail =>
          if dbUser.nonEmpty then Left(s"duplicate flag: --db-user\n$StageUsage")
          else loop(tail, inputPath, batchId, dbUrl, Some(value), dbPassword, scenarioId, trialCount, parallelism, simulationTicks, readConsistency, tableName)

        case "--db-password" :: value :: tail =>
          if dbPassword.nonEmpty then Left(s"duplicate flag: --db-password\n$StageUsage")
          else loop(tail, inputPath, batchId, dbUrl, dbUser, Some(value), scenarioId, trialCount, parallelism, simulationTicks, readConsistency, tableName)

        case "--scenario-id" :: value :: tail =>
          if scenarioId.nonEmpty then Left(s"duplicate flag: --scenario-id\n$StageUsage")
          else loop(tail, inputPath, batchId, dbUrl, dbUser, dbPassword, Some(value), trialCount, parallelism, simulationTicks, readConsistency, tableName)

        case "--trial-count" :: value :: tail =>
          if trialCount.nonEmpty then Left(s"duplicate flag: --trial-count\n$StageUsage")
          else parseIntFlag("--trial-count", value, StageUsage).flatMap(parsed =>
            loop(tail, inputPath, batchId, dbUrl, dbUser, dbPassword, scenarioId, Some(parsed), parallelism, simulationTicks, readConsistency, tableName)
          )

        case "--parallelism" :: value :: tail =>
          if parallelism.nonEmpty then Left(s"duplicate flag: --parallelism\n$StageUsage")
          else parseIntFlag("--parallelism", value, StageUsage).flatMap(parsed =>
            loop(tail, inputPath, batchId, dbUrl, dbUser, dbPassword, scenarioId, trialCount, Some(parsed), simulationTicks, readConsistency, tableName)
          )

        case "--simulation-ticks" :: value :: tail =>
          if simulationTicks.nonEmpty then Left(s"duplicate flag: --simulation-ticks\n$StageUsage")
          else parseLongFlag("--simulation-ticks", value, StageUsage).flatMap(parsed =>
            loop(tail, inputPath, batchId, dbUrl, dbUser, dbPassword, scenarioId, trialCount, parallelism, Some(parsed), readConsistency, tableName)
          )

        case "--read-consistency" :: value :: tail =>
          if readConsistency.nonEmpty then Left(s"duplicate flag: --read-consistency\n$StageUsage")
          else loop(tail, inputPath, batchId, dbUrl, dbUser, dbPassword, scenarioId, trialCount, parallelism, simulationTicks, Some(value), tableName)

        case "--table-name" :: value :: tail =>
          if tableName.nonEmpty then Left(s"duplicate flag: --table-name\n$StageUsage")
          else loop(tail, inputPath, batchId, dbUrl, dbUser, dbPassword, scenarioId, trialCount, parallelism, simulationTicks, readConsistency, Some(value))

        case flag :: Nil if flag.startsWith("--") =>
          Left(s"missing value for flag: $flag\n$StageUsage")

        case flag :: _ if flag.startsWith("--") =>
          Left(s"unknown flag: $flag\n$StageUsage")

        case value :: _ =>
          Left(s"unexpected argument: $value\n$StageUsage")

    loop(args, None, None, None, None, None, None, None, None, None, None, None)

  private def parseView(
                         args: List[String]
                       ): Either[String, OrderTrackingBridgeCommand.View] =
    val defaults = OrderTrackingScenarioConfig.phase2Default

    def loop(
              remaining: List[String],
              grafanaBaseUrl: Option[String],
              batchId: Option[String],
              scenarioId: Option[String]
            ): Either[String, OrderTrackingBridgeCommand.View] =
      remaining match
        case Nil =>
          batchId.toRight(s"missing required flag: --batch-id\n$ViewUsage").map { id =>
            OrderTrackingBridgeCommand.View(
              grafanaBaseUrl = grafanaBaseUrl.getOrElse("http://localhost:3000"),
              batchId = id,
              scenarioId = scenarioId.getOrElse(defaults.scenarioId)
            )
          }

        case "--grafana-base-url" :: value :: tail =>
          if grafanaBaseUrl.nonEmpty then Left(s"duplicate flag: --grafana-base-url\n$ViewUsage")
          else loop(tail, Some(value), batchId, scenarioId)

        case "--batch-id" :: value :: tail =>
          if batchId.nonEmpty then Left(s"duplicate flag: --batch-id\n$ViewUsage")
          else loop(tail, grafanaBaseUrl, Some(value), scenarioId)

        case "--scenario-id" :: value :: tail =>
          if scenarioId.nonEmpty then Left(s"duplicate flag: --scenario-id\n$ViewUsage")
          else loop(tail, grafanaBaseUrl, batchId, Some(value))

        case flag :: Nil if flag.startsWith("--") =>
          Left(s"missing value for flag: $flag\n$ViewUsage")

        case flag :: _ if flag.startsWith("--") =>
          Left(s"unknown flag: $flag\n$ViewUsage")

        case value :: _ =>
          Left(s"unexpected argument: $value\n$ViewUsage")

    loop(args, None, None, None)

  private def parseIntFlag(name: String, value: String, usage: String): Either[String, Int] =
    Try(value.toInt).toEither.left.map(_ => s"invalid integer for $name: $value\n$usage").flatMap { parsed =>
      if parsed < 1 then Left(s"$name must be at least 1\n$usage")
      else Right(parsed)
    }

  private def parseLongFlag(name: String, value: String, usage: String): Either[String, Long] =
    Try(value.toLong).toEither.left.map(_ => s"invalid long for $name: $value\n$usage").flatMap { parsed =>
      if parsed < 1L then Left(s"$name must be at least 1\n$usage")
      else Right(parsed)
    }

  private def defaultBatchId(now: ZonedDateTime): String =
    val formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    s"order-tracking-phase2-${now.format(formatter)}"

object OrderTrackingPhase2DemoRunner:
  val Phase2BaseSeed: Long = 20260418L

  def run(
           options: OrderTrackingPhase2DemoOptions
         )(using ActorSystem, Materializer, ExecutionContext): Future[DemoExportBundle] =
    val scenarioConfig = OrderTrackingScenarioConfig.phase2Default.copy(
      trialCount = options.trialCount,
      parallelism = options.parallelism,
      simulationTicks = options.simulationTicks
    )

    val runner = OrderTrackingSingleTrialRunner()
    val executor = FutureMultiTrialExecutor[OrderTrackingScenarioConfig](runner)

    executor
      .runTrials(
        config = scenarioConfig,
        exec = TrialExecutionConfig(
          trialCount = options.trialCount,
          parallelism = options.parallelism,
          baseSeed = Phase2BaseSeed
        )
      )
      .map(DemoReportBuilder.build)

  def emit(
            options: OrderTrackingPhase2DemoOptions,
            bundle: DemoExportBundle
          ): String =
    val rendered = DemoJsonlExporter.render(bundle.records)

    options.outputPath match
      case Some(path) =>
        DemoJsonlExporter.write(path, bundle.records)
        s"wrote ${bundle.records.size} records for scenario ${bundle.aggregate.scenarioId} to $path"

      case None =>
        rendered

  /**
   * Memory-bounded generate-to-file path. Processes one trial at a time (respecting parallelism),
   * serialises per-trial records to the output file immediately so TrialResult objects can be GC'd,
   * and keeps only compact Welford accumulators for the aggregate — memory is O(ticks × metrics),
   * independent of trial count.
   */
  def generateToFile(
    outputPath: Path,
    trialCount: Int,
    parallelism: Int,
    simulationTicks: Long
  )(using ActorSystem, Materializer, ExecutionContext): Future[String] =
    import org.apache.pekko.stream.scaladsl.{Source => PekkoSource}
    import org.json4s.jackson.Serialization
    given org.json4s.DefaultFormats = org.json4s.DefaultFormats

    val scenarioConfig = OrderTrackingScenarioConfig.phase2Default.copy(
      trialCount = trialCount,
      parallelism = parallelism,
      simulationTicks = simulationTicks
    )
    val runner = OrderTrackingSingleTrialRunner()
    val exec = TrialExecutionConfig(trialCount, parallelism, Phase2BaseSeed)

    val writer = new java.io.BufferedWriter(
      new java.io.OutputStreamWriter(
        java.nio.file.Files.newOutputStream(outputPath),
        java.nio.charset.StandardCharsets.UTF_8
      )
    )

    case class AggState(
      mcAgg: IncrementalMonteCarloAgg,
      windowedAgg: Map[WindowSizeSeconds, IncrementalWindowedAgg],
      recordCount: Int
    )

    val initState = AggState(
      mcAgg = IncrementalMonteCarloAgg(scenarioConfig.scenarioId),
      windowedAgg = WindowSizeSeconds.phase1Values.map(ws => ws -> IncrementalWindowedAgg(ws)).toMap,
      recordCount = 0
    )

    def writeRecord(rec: DemoExportRecord): Unit =
      writer.write(Serialization.write(rec))
      writer.newLine()

    PekkoSource(exec.trialRunConfigs)
      .mapAsync(parallelism)(run => runner.runTrial(scenarioConfig, run))
      .runFold(initState) { (state, trial) =>
        val perTrialRecs =
          DemoExportRecord.fromTrialResult(trial) ++
            WindowSizeSeconds.phase1Values.flatMap { ws =>
              DemoExportRecord.fromWindowedTrialTimeSeries(
                trial.scenarioId, trial.trialId,
                TimeWindowRollups.rollupTrialTimeSeries(trial.timeSeries, ws)
              )
            }
        perTrialRecs.foreach(writeRecord)
        state.copy(
          mcAgg = state.mcAgg.addTrial(trial),
          windowedAgg = state.windowedAgg.map { case (ws, wagg) => ws -> wagg.addTrial(trial.timeSeries) },
          recordCount = state.recordCount + perTrialRecs.size
        )
      }
      .map { finalState =>
        val mcResult = finalState.mcAgg.toMonteCarloResult
        val aggRecs =
          DemoExportRecord.fromMonteCarloResult(mcResult) ++
            WindowSizeSeconds.phase1Values.flatMap { ws =>
              DemoExportRecord.fromAggregatedWindowedTimeSeries(
                mcResult.scenarioId, mcResult.trialCount,
                finalState.windowedAgg(ws).toAggregatedWindowedPoints
              )
            }
        aggRecs.foreach(writeRecord)
        writer.flush()
        writer.close()
        s"wrote ${finalState.recordCount + aggRecs.size} records for scenario ${mcResult.scenarioId} to $outputPath"
      }
      .andThen { case scala.util.Failure(_) => Try(writer.close()) }(ExecutionContext.parasitic)

final case class StagedDemoRecord(
                                   recordType: String,
                                   scenarioId: String,
                                   trialId: Option[Int],
                                   tick: Option[Long],
                                   windowSizeSeconds: Option[Int],
                                   windowStartTick: Option[Long],
                                   metric: String,
                                   statistic: Option[String],
                                   value: BigDecimal
                                 )

object OrderTrackingPostgresBridge:
  private given Formats = DefaultFormats
  private val JdbcFlushSize = 1000
  private val SpinnerChars = Array('|', '/', '-', '\\')

  def stage(
             inputPath: Path,
             metadata: BatchMetadata,
             dbUrl: String,
             dbUser: String,
             dbPassword: String
           ): Int =
    val connection = DriverManager.getConnection(dbUrl, dbUser, dbPassword)
    try
      connection.setAutoCommit(false)
      insertBatch(connection, metadata)
      val count = insertRecordsStreaming(connection, metadata.batchId, metadata.scenarioId, inputPath)
      require(count > 0, "JSONL input must not be empty")
      connection.commit()
      count
    catch
      case t: Throwable =>
        Try(connection.rollback())
        throw t
    finally
      connection.close()

  private def insertRecordsStreaming(
    connection: Connection,
    batchId: String,
    expectedScenarioId: String,
    inputPath: Path
  ): Int =
    val sql =
      """insert into stochastacy_demo.demo_records
        |(batch_id, record_type, scenario_id, trial_id, tick, window_size_seconds, window_start_tick, metric, statistic, "value")
        |values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin
    val stmt = connection.prepareStatement(sql)
    var count = 0
    var spinnerIdx = 0

    def flushBatch(): Unit =
      stmt.executeBatch()
      print(s"\r${SpinnerChars(spinnerIdx % SpinnerChars.length)} staging...")
      System.out.flush()
      spinnerIdx += 1

    print(s"\r${SpinnerChars(0)} staging...")
    System.out.flush()
    try
      val fileSource = Source.fromFile(inputPath.toFile, "UTF-8")
      try
        for line <- fileSource.getLines() do
          val trimmed = line.trim
          if trimmed.nonEmpty then
            val record = parseRecord(trimmed)
            require(
              record.scenarioId == expectedScenarioId,
              s"JSONL record has scenarioId '${record.scenarioId}', expected '$expectedScenarioId'"
            )
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
            if count % JdbcFlushSize == 0 then flushBatch()
        if count % JdbcFlushSize != 0 then flushBatch()
      finally
        fileSource.close()
      println()
      count
    finally
      stmt.close()

  def parseJsonl(
                  jsonl: String,
                  expectedScenarioId: String
                ): Vector[StagedDemoRecord] =
    val lines = jsonl.linesIterator.map(_.trim).filter(_.nonEmpty).toVector
    require(lines.nonEmpty, "JSONL input must not be empty")

    val records = lines.map(parseRecord)
    require(
      records.forall(_.scenarioId == expectedScenarioId),
      s"JSONL records must all have scenarioId = $expectedScenarioId"
    )
    records

  def loadSchema(connection: Connection): Unit =
    val schemaSql =
      Source.fromResource("stochastacy/examples/ordertracking/postgres/001-schema.sql").mkString
    schemaSql
      .split(";")
      .map(_.trim)
      .filter(_.nonEmpty)
      .foreach { statement =>
        val stmt = connection.createStatement()
        try stmt.execute(statement)
        finally stmt.close()
      }

  private def parseRecord(line: String): StagedDemoRecord =
    val json = parse(line)
    val recordType = (json \ "recordType").extract[String]
    val scenarioId = (json \ "scenarioId").extract[String]
    val metric = (json \ "metric").extract[String]
    val value = (json \ "value").extract[BigDecimal]

    StagedDemoRecord(
      recordType = recordType,
      scenarioId = scenarioId,
      trialId = (json \ "trialId").extractOpt[Int],
      tick = (json \ "tick").extractOpt[Long],
      windowSizeSeconds = (json \ "windowSizeSeconds").extractOpt[Int],
      windowStartTick = (json \ "windowStartTick").extractOpt[Long],
      metric = metric,
      statistic = (json \ "statistic").extractOpt[String],
      value = value
    )

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
    finally
      stmt.close()

  private def insertRecords(
                             connection: Connection,
                             batchId: String,
                             records: Vector[StagedDemoRecord]
                           ): Unit =
    val sql =
      """insert into stochastacy_demo.demo_records
        |(batch_id, record_type, scenario_id, trial_id, tick, window_size_seconds, window_start_tick, metric, statistic, "value")
        |values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin
    val stmt = connection.prepareStatement(sql)
    try
      records.foreach { record =>
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
      }
      stmt.executeBatch()
    finally
      stmt.close()

object OrderTrackingGrafanaView:
  private val DashboardUid = "ips-phase2-order-tracking"
  private val DashboardSlug = "ips-phase-2-order-tracking-dynamodb-simulation"

  def url(grafanaBaseUrl: String, batchId: String, scenarioId: String): String =
    val base = grafanaBaseUrl.stripSuffix("/")
    s"$base/d/$DashboardUid/$DashboardSlug?var-batch_id=${encode(batchId)}&var-scenarioId=${encode(scenarioId)}"

  private def encode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)

@main def OrderTrackingPhase2Bridge(args: String*): Unit =
  OrderTrackingPhase2BridgeCli.parseArgs(args) match
    case Left(error) =>
      System.err.println(error)
      sys.exit(1)

    case Right(command) =>
      command match
        case generate: OrderTrackingBridgeCommand.Generate =>
          given ActorSystem = ActorSystem("OrderTrackingPhase2BridgeGenerate")
          given Materializer = Materializer.matFromSystem
          given ExecutionContext = summon[ActorSystem].dispatcher

          val outcome =
            try
              val message = Await.result(
                OrderTrackingPhase2DemoRunner.generateToFile(
                  outputPath = generate.outputPath,
                  trialCount = generate.trialCount,
                  parallelism = generate.parallelism,
                  simulationTicks = generate.simulationTicks
                ),
                10.minutes
              )
              println(message)
              println(s"generated batch ${generate.batchId} to ${generate.outputPath}")
              Success(())
            catch
              case t: Throwable => Failure(t)

          Await.result(summon[ActorSystem].terminate(), 30.seconds)
          outcome match
            case Success(_) => ()
            case Failure(t) =>
              System.err.println(s"generate failed: ${t.getMessage}")
              sys.exit(1)

        case stage: OrderTrackingBridgeCommand.Stage =>
          try
            val count = OrderTrackingPostgresBridge.stage(
              inputPath = stage.inputPath,
              metadata = stage.metadata,
              dbUrl = stage.dbUrl,
              dbUser = stage.dbUser,
              dbPassword = stage.dbPassword
            )
            println(s"staged $count records for batch ${stage.metadata.batchId} into ${stage.dbUrl}")
          catch
            case t: Throwable =>
              System.err.println(s"stage failed: ${t.getMessage}")
              sys.exit(1)

        case view: OrderTrackingBridgeCommand.View =>
          println(
            OrderTrackingGrafanaView.url(
              grafanaBaseUrl = view.grafanaBaseUrl,
              batchId = view.batchId,
              scenarioId = view.scenarioId
            )
          )
