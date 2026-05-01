package stochastacy.examples.thermostatfleet

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import stochastacy.demo.{DemoExportBundle, DemoExportRecord, DemoJsonlExporter, DemoReportBuilder, FutureMultiTrialExecutor, IncrementalMonteCarloAgg, IncrementalWindowedAgg, TimeWindowRollups, TrialExecutionConfig, WindowSizeSeconds}
import stochastacy.examples.ordertracking.{BatchMetadata, OrderTrackingPostgresBridge}

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.format.DateTimeFormatter
import java.time.{ZoneOffset, ZonedDateTime}
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

sealed trait ThermostatFleetBridgeCommand

object ThermostatFleetBridgeCommand:
  final case class Generate(
    batchId: String,
    outputPath: Path,
    trialCount: Int,
    parallelism: Int,
    simulationTicks: Long,
    mode: String,
    initialProvisionedWcu: Long,
    adjustedProvisionedWcu: Long
  ) extends ThermostatFleetBridgeCommand

  final case class Stage(
    inputPath: Path,
    metadata: BatchMetadata,
    dbUrl: String,
    dbUser: String,
    dbPassword: String
  ) extends ThermostatFleetBridgeCommand

  final case class View(
    grafanaBaseUrl: String,
    batchId: String,
    scenarioId: String,
    mode: String
  ) extends ThermostatFleetBridgeCommand

object ThermostatFleetBridgeCli:
  private val GenerateUsage =
    "usage: ThermostatFleetBridge generate --output <path> --mode <single-region|multi-region|mixed-mode> [--batch-id <id>] [--trial-count <int>] [--parallelism <int>] [--simulation-ticks <long>] [--initial-provisioned-wcu <long>] [--adjusted-provisioned-wcu <long>]"
  private val StageUsage =
    "usage: ThermostatFleetBridge stage --input <path> --batch-id <id> --db-url <jdbc-url> --db-user <user> --db-password <password> --trial-count <int> --parallelism <int> --simulation-ticks <long> [--mode <single-region|multi-region|mixed-mode>]"
  private val ViewUsage =
    "usage: ThermostatFleetBridge view --batch-id <id> [--mode <single-region|multi-region|mixed-mode>] [--grafana-base-url <url>]"
  private val TopLevelUsage =
    s"""usage:
       |  $GenerateUsage
       |  $StageUsage
       |  $ViewUsage""".stripMargin

  def parseArgs(
    args: Seq[String],
    now: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC)
  ): Either[String, ThermostatFleetBridgeCommand] =
    args.toList match
      case "generate" :: tail => parseGenerate(tail, now)
      case "stage" :: tail => parseStage(tail)
      case "view" :: tail => parseView(tail)
      case Nil => Left(TopLevelUsage)
      case subcommand :: _ => Left(s"unknown subcommand: $subcommand\n$TopLevelUsage")

  private def parseGenerate(
    args: List[String],
    now: ZonedDateTime
  ): Either[String, ThermostatFleetBridgeCommand.Generate] =
    val defaults = ThermostatFleetScenarioConfig.singleRegionDefault

    def loop(
      remaining: List[String],
      outputPath: Option[Path],
      batchId: Option[String],
      trialCount: Option[Int],
      parallelism: Option[Int],
      simulationTicks: Option[Long],
      mode: Option[String],
      initialProvisionedWcu: Option[Long],
      adjustedProvisionedWcu: Option[Long]
    ): Either[String, ThermostatFleetBridgeCommand.Generate] =
      remaining match
        case Nil =>
          val mmDefaults = ThermostatFleetMixedModeConfig()
          for
            path <- outputPath.toRight(s"missing required flag: --output\n$GenerateUsage")
            m <- mode.toRight(s"missing required flag: --mode\n$GenerateUsage")
            _ <- validateMode(m, GenerateUsage)
          yield ThermostatFleetBridgeCommand.Generate(
            batchId = batchId.getOrElse(defaultBatchId(now, m)),
            outputPath = path,
            trialCount = trialCount.getOrElse(defaults.trialCount),
            parallelism = parallelism.getOrElse(defaults.parallelism),
            simulationTicks = simulationTicks.getOrElse(defaults.simulationTicks),
            mode = m,
            initialProvisionedWcu = initialProvisionedWcu.getOrElse(mmDefaults.initialProvisionedWcu),
            adjustedProvisionedWcu = adjustedProvisionedWcu.getOrElse(mmDefaults.adjustedProvisionedWcu)
          )

        case "--output" :: value :: tail =>
          if outputPath.nonEmpty then Left(s"duplicate flag: --output\n$GenerateUsage")
          else loop(tail, Some(Path.of(value)), batchId, trialCount, parallelism, simulationTicks, mode, initialProvisionedWcu, adjustedProvisionedWcu)

        case "--batch-id" :: value :: tail =>
          if batchId.nonEmpty then Left(s"duplicate flag: --batch-id\n$GenerateUsage")
          else loop(tail, outputPath, Some(value), trialCount, parallelism, simulationTicks, mode, initialProvisionedWcu, adjustedProvisionedWcu)

        case "--trial-count" :: value :: tail =>
          if trialCount.nonEmpty then Left(s"duplicate flag: --trial-count\n$GenerateUsage")
          else parseIntFlag("--trial-count", value, GenerateUsage).flatMap(parsed =>
            loop(tail, outputPath, batchId, Some(parsed), parallelism, simulationTicks, mode, initialProvisionedWcu, adjustedProvisionedWcu))

        case "--parallelism" :: value :: tail =>
          if parallelism.nonEmpty then Left(s"duplicate flag: --parallelism\n$GenerateUsage")
          else parseIntFlag("--parallelism", value, GenerateUsage).flatMap(parsed =>
            loop(tail, outputPath, batchId, trialCount, Some(parsed), simulationTicks, mode, initialProvisionedWcu, adjustedProvisionedWcu))

        case "--simulation-ticks" :: value :: tail =>
          if simulationTicks.nonEmpty then Left(s"duplicate flag: --simulation-ticks\n$GenerateUsage")
          else parseLongFlag("--simulation-ticks", value, GenerateUsage).flatMap(parsed =>
            loop(tail, outputPath, batchId, trialCount, parallelism, Some(parsed), mode, initialProvisionedWcu, adjustedProvisionedWcu))

        case "--mode" :: value :: tail =>
          if mode.nonEmpty then Left(s"duplicate flag: --mode\n$GenerateUsage")
          else loop(tail, outputPath, batchId, trialCount, parallelism, simulationTicks, Some(value), initialProvisionedWcu, adjustedProvisionedWcu)

        case "--initial-provisioned-wcu" :: value :: tail =>
          if initialProvisionedWcu.nonEmpty then Left(s"duplicate flag: --initial-provisioned-wcu\n$GenerateUsage")
          else parseLongFlag("--initial-provisioned-wcu", value, GenerateUsage).flatMap(parsed =>
            loop(tail, outputPath, batchId, trialCount, parallelism, simulationTicks, mode, Some(parsed), adjustedProvisionedWcu))

        case "--adjusted-provisioned-wcu" :: value :: tail =>
          if adjustedProvisionedWcu.nonEmpty then Left(s"duplicate flag: --adjusted-provisioned-wcu\n$GenerateUsage")
          else parseLongFlag("--adjusted-provisioned-wcu", value, GenerateUsage).flatMap(parsed =>
            loop(tail, outputPath, batchId, trialCount, parallelism, simulationTicks, mode, initialProvisionedWcu, Some(parsed)))

        case flag :: Nil if flag.startsWith("--") =>
          Left(s"missing value for flag: $flag\n$GenerateUsage")

        case flag :: _ if flag.startsWith("--") =>
          Left(s"unknown flag: $flag\n$GenerateUsage")

        case value :: _ =>
          Left(s"unexpected argument: $value\n$GenerateUsage")

    loop(args, None, None, None, None, None, None, None, None)

  private def parseStage(args: List[String]): Either[String, ThermostatFleetBridgeCommand.Stage] =
    def loop(
      remaining: List[String],
      inputPath: Option[Path],
      batchId: Option[String],
      dbUrl: Option[String],
      dbUser: Option[String],
      dbPassword: Option[String],
      trialCount: Option[Int],
      parallelism: Option[Int],
      simulationTicks: Option[Long],
      mode: Option[String]
    ): Either[String, ThermostatFleetBridgeCommand.Stage] =
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
            val resolvedMode = mode.getOrElse("single-region")
            val (scenarioId, baseSeed, readConsistency, tableName) = resolvedMode match
              case "mixed-mode" =>
                val c  = ThermostatFleetMixedModeConfig()
                val sc = c.toScenarioConfig
                (c.scenarioId, ThermostatFleetMixedModeDemoRunner.BaseSeed, sc.readConsistency.toString, sc.tableName)
              case "multi-region" =>
                val c = ThermostatFleetScenarioConfig.multiRegionDefault
                (c.scenarioId, ThermostatFleetDemoRunner.BaseSeed, c.readConsistency.toString, c.tableName)
              case _ =>
                val c = ThermostatFleetScenarioConfig.singleRegionDefault
                (c.scenarioId, ThermostatFleetDemoRunner.BaseSeed, c.readConsistency.toString, c.tableName)
            ThermostatFleetBridgeCommand.Stage(
              inputPath = path,
              metadata = BatchMetadata(
                batchId = id,
                scenarioId = scenarioId,
                trialCount = tc,
                parallelism = p,
                simulationTicks = ticks,
                baseSeed = baseSeed,
                readConsistency = readConsistency,
                tableName = tableName,
                sourceJsonlPath = Some(path.toString)
              ),
              dbUrl = jdbcUrl,
              dbUser = user,
              dbPassword = password
            )

        case "--input" :: value :: tail =>
          loop(tail, Some(Path.of(value)), batchId, dbUrl, dbUser, dbPassword, trialCount, parallelism, simulationTicks, mode)
        case "--batch-id" :: value :: tail =>
          loop(tail, inputPath, Some(value), dbUrl, dbUser, dbPassword, trialCount, parallelism, simulationTicks, mode)
        case "--db-url" :: value :: tail =>
          loop(tail, inputPath, batchId, Some(value), dbUser, dbPassword, trialCount, parallelism, simulationTicks, mode)
        case "--db-user" :: value :: tail =>
          loop(tail, inputPath, batchId, dbUrl, Some(value), dbPassword, trialCount, parallelism, simulationTicks, mode)
        case "--db-password" :: value :: tail =>
          loop(tail, inputPath, batchId, dbUrl, dbUser, Some(value), trialCount, parallelism, simulationTicks, mode)
        case "--trial-count" :: value :: tail =>
          parseIntFlag("--trial-count", value, StageUsage).flatMap(parsed =>
            loop(tail, inputPath, batchId, dbUrl, dbUser, dbPassword, Some(parsed), parallelism, simulationTicks, mode))
        case "--parallelism" :: value :: tail =>
          parseIntFlag("--parallelism", value, StageUsage).flatMap(parsed =>
            loop(tail, inputPath, batchId, dbUrl, dbUser, dbPassword, trialCount, Some(parsed), simulationTicks, mode))
        case "--simulation-ticks" :: value :: tail =>
          parseLongFlag("--simulation-ticks", value, StageUsage).flatMap(parsed =>
            loop(tail, inputPath, batchId, dbUrl, dbUser, dbPassword, trialCount, parallelism, Some(parsed), mode))
        case "--mode" :: value :: tail =>
          loop(tail, inputPath, batchId, dbUrl, dbUser, dbPassword, trialCount, parallelism, simulationTicks, Some(value))
        case flag :: Nil if flag.startsWith("--") =>
          Left(s"missing value for flag: $flag\n$StageUsage")
        case flag :: _ if flag.startsWith("--") =>
          Left(s"unknown flag: $flag\n$StageUsage")
        case value :: _ =>
          Left(s"unexpected argument: $value\n$StageUsage")

    loop(args, None, None, None, None, None, None, None, None, None)

  private def parseView(args: List[String]): Either[String, ThermostatFleetBridgeCommand.View] =
    def loop(
      remaining: List[String],
      grafanaBaseUrl: Option[String],
      batchId: Option[String],
      mode: Option[String]
    ): Either[String, ThermostatFleetBridgeCommand.View] =
      remaining match
        case Nil =>
          batchId.toRight(s"missing required flag: --batch-id\n$ViewUsage").map { id =>
            val resolvedMode = mode.getOrElse("single-region")
            val scenarioId = resolvedMode match
              case "mixed-mode"   => ThermostatFleetMixedModeConfig().scenarioId
              case "multi-region" => ThermostatFleetScenarioConfig.multiRegionDefault.scenarioId
              case _              => ThermostatFleetScenarioConfig.singleRegionDefault.scenarioId
            ThermostatFleetBridgeCommand.View(
              grafanaBaseUrl = grafanaBaseUrl.getOrElse("http://localhost:3000"),
              batchId = id,
              scenarioId = scenarioId,
              mode = resolvedMode
            )
          }
        case "--grafana-base-url" :: value :: tail =>
          loop(tail, Some(value), batchId, mode)
        case "--batch-id" :: value :: tail =>
          loop(tail, grafanaBaseUrl, Some(value), mode)
        case "--mode" :: value :: tail =>
          loop(tail, grafanaBaseUrl, batchId, Some(value))
        case flag :: Nil if flag.startsWith("--") =>
          Left(s"missing value for flag: $flag\n$ViewUsage")
        case flag :: _ if flag.startsWith("--") =>
          Left(s"unknown flag: $flag\n$ViewUsage")
        case value :: _ =>
          Left(s"unexpected argument: $value\n$ViewUsage")

    loop(args, None, None, None)

  private def validateMode(mode: String, usage: String): Either[String, Unit] =
    if mode == "single-region" || mode == "multi-region" || mode == "mixed-mode" then Right(())
    else Left(s"--mode must be 'single-region', 'multi-region', or 'mixed-mode', got: $mode\n$usage")

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

  private def defaultBatchId(now: ZonedDateTime, mode: String): String =
    val formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    s"thermostat-fleet-$mode-${now.format(formatter)}"

object ThermostatFleetDemoRunner:
  val BaseSeed: Long = 20260426L

  def run(
    trialCount: Int,
    parallelism: Int,
    simulationTicks: Long,
    mode: String
  )(using ActorSystem, Materializer, ExecutionContext): Future[DemoExportBundle] =
    val baseConfig = if mode == "multi-region" then
      ThermostatFleetScenarioConfig.multiRegionDefault
    else
      ThermostatFleetScenarioConfig.singleRegionDefault
    val scenarioConfig = baseConfig.copy(
      trialCount = trialCount,
      parallelism = parallelism,
      simulationTicks = simulationTicks
    )

    val runner = ThermostatFleetSingleTrialRunner()
    val executor = FutureMultiTrialExecutor[ThermostatFleetScenarioConfig](runner)

    executor
      .runTrials(
        config = scenarioConfig,
        exec = TrialExecutionConfig(
          trialCount = trialCount,
          parallelism = parallelism,
          baseSeed = BaseSeed
        )
      )
      .map(DemoReportBuilder.build)

  def generateToFile(
    outputPath: Path,
    trialCount: Int,
    parallelism: Int,
    simulationTicks: Long,
    mode: String
  )(using ActorSystem, Materializer, ExecutionContext): Future[String] =
    import org.apache.pekko.stream.scaladsl.{Source => PekkoSource}
    import org.json4s.jackson.Serialization
    given org.json4s.DefaultFormats = org.json4s.DefaultFormats

    val baseConfig = if mode == "multi-region" then
      ThermostatFleetScenarioConfig.multiRegionDefault
    else
      ThermostatFleetScenarioConfig.singleRegionDefault
    val scenarioConfig = baseConfig.copy(
      trialCount = trialCount,
      parallelism = parallelism,
      simulationTicks = simulationTicks
    )
    val runner = ThermostatFleetSingleTrialRunner()
    val exec = TrialExecutionConfig(trialCount, parallelism, BaseSeed)

    val writer = new java.io.BufferedWriter(
      new java.io.OutputStreamWriter(
        java.nio.file.Files.newOutputStream(outputPath),
        java.nio.charset.StandardCharsets.UTF_8
      )
    )

    case class AggState(
      mcAgg: IncrementalMonteCarloAgg,
      windowedAgg: Map[WindowSizeSeconds, IncrementalWindowedAgg],
      recordCount: Int,
      completedTrials: Int
    )

    val initState = AggState(
      mcAgg = IncrementalMonteCarloAgg(scenarioConfig.scenarioId),
      windowedAgg = WindowSizeSeconds.phase1Values.map(ws => ws -> IncrementalWindowedAgg(ws)).toMap,
      recordCount = 0,
      completedTrials = 0
    )

    def writeRecord(rec: DemoExportRecord): Unit =
      writer.write(Serialization.write(rec))
      writer.newLine()

    val barWidth = 40
    def printProgress(completed: Int): Unit =
      val pct = if trialCount == 0 then 100 else (completed * 100) / trialCount
      val filled = if trialCount == 0 then barWidth else (completed * barWidth) / trialCount
      val bar = "█" * filled + "░" * (barWidth - filled)
      print(s"\r[$bar] $completed/$trialCount ($pct%)")
      System.out.flush()

    printProgress(0)

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
        val newCompleted = state.completedTrials + 1
        printProgress(newCompleted)
        state.copy(
          mcAgg = state.mcAgg.addTrial(trial),
          windowedAgg = state.windowedAgg.map { case (ws, wagg) => ws -> wagg.addTrial(trial.timeSeries) },
          recordCount = state.recordCount + perTrialRecs.size,
          completedTrials = newCompleted
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
        println()
        s"wrote ${finalState.recordCount + aggRecs.size} records for scenario ${mcResult.scenarioId} to $outputPath"
      }
      .andThen { case scala.util.Failure(_) => println(); scala.util.Try(writer.close()) }(ExecutionContext.parasitic)

object ThermostatFleetGrafanaView:
  private val DashboardUid = "ips-phase3-thermostat-fleet"
  private val DashboardSlug = "ips-phase-3-thermostat-fleet-dynamodb-simulation"

  def url(grafanaBaseUrl: String, batchId: String, scenarioId: String): String =
    val base = grafanaBaseUrl.stripSuffix("/")
    s"$base/d/$DashboardUid/$DashboardSlug?var-batch_id=${encode(batchId)}&var-scenarioId=${encode(scenarioId)}"

  private def encode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)

object ThermostatFleetMixedModeDemoRunner:
  val BaseSeed: Long = 20260426L

  def generateToFile(
    outputPath: Path,
    trialCount: Int,
    parallelism: Int,
    simulationTicks: Long,
    initialProvisionedWcu: Long,
    adjustedProvisionedWcu: Long
  )(using ActorSystem, Materializer, ExecutionContext): Future[String] =
    import org.apache.pekko.stream.scaladsl.{Source => PekkoSource}
    import org.json4s.jackson.Serialization
    given org.json4s.DefaultFormats = org.json4s.DefaultFormats

    val modeSwitchTick    = simulationTicks / 3
    val capacityAdjustTick = simulationTicks * 2 / 3
    val config = ThermostatFleetMixedModeConfig(
      trialCount             = trialCount,
      parallelism            = parallelism,
      simulationTicks        = simulationTicks,
      modeSwitchTick         = modeSwitchTick,
      capacityAdjustTick     = capacityAdjustTick,
      initialProvisionedWcu  = initialProvisionedWcu,
      adjustedProvisionedWcu = adjustedProvisionedWcu
    )
    val runner = ThermostatFleetMixedModeSingleTrialRunner()
    val exec   = TrialExecutionConfig(trialCount, parallelism, BaseSeed)

    val writer = new java.io.BufferedWriter(
      new java.io.OutputStreamWriter(
        java.nio.file.Files.newOutputStream(outputPath),
        java.nio.charset.StandardCharsets.UTF_8
      )
    )

    case class AggState(
      mcAgg: IncrementalMonteCarloAgg,
      windowedAgg: Map[WindowSizeSeconds, IncrementalWindowedAgg],
      recordCount: Int,
      completedTrials: Int
    )

    val initState = AggState(
      mcAgg = IncrementalMonteCarloAgg(config.scenarioId),
      windowedAgg = WindowSizeSeconds.phase1Values.map(ws => ws -> IncrementalWindowedAgg(ws)).toMap,
      recordCount = 0,
      completedTrials = 0
    )

    def writeRecord(rec: DemoExportRecord): Unit =
      writer.write(Serialization.write(rec))
      writer.newLine()

    val barWidth = 40
    def printProgress(completed: Int): Unit =
      val pct    = if trialCount == 0 then 100 else (completed * 100) / trialCount
      val filled = if trialCount == 0 then barWidth else (completed * barWidth) / trialCount
      val bar    = "█" * filled + "░" * (barWidth - filled)
      print(s"\r[$bar] $completed/$trialCount ($pct%)")
      System.out.flush()

    printProgress(0)

    PekkoSource(exec.trialRunConfigs)
      .mapAsync(parallelism)(run => runner.runTrial(config, run))
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
        val newCompleted = state.completedTrials + 1
        printProgress(newCompleted)
        state.copy(
          mcAgg = state.mcAgg.addTrial(trial),
          windowedAgg = state.windowedAgg.map { case (ws, wagg) => ws -> wagg.addTrial(trial.timeSeries) },
          recordCount = state.recordCount + perTrialRecs.size,
          completedTrials = newCompleted
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
        println()
        s"wrote ${finalState.recordCount + aggRecs.size} records for scenario ${mcResult.scenarioId} to $outputPath"
      }
      .andThen { case scala.util.Failure(_) => println(); scala.util.Try(writer.close()) }(ExecutionContext.parasitic)

object ThermostatFleetMixedModeGrafanaView:
  private val DashboardUid  = "ips-phase4-mixed-mode"
  private val DashboardSlug = "thermostat-fleet-mixed-billing-mode-demo"

  def url(grafanaBaseUrl: String, batchId: String, scenarioId: String): String =
    val base = grafanaBaseUrl.stripSuffix("/")
    s"$base/d/$DashboardUid/$DashboardSlug?var-batch_id=${encode(batchId)}&var-scenarioId=${encode(scenarioId)}"

  private def encode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)

@main def ThermostatFleetBridge(args: String*): Unit =
  ThermostatFleetBridgeCli.parseArgs(args) match
    case Left(error) =>
      System.err.println(error)
      sys.exit(1)

    case Right(command) =>
      command match
        case generate: ThermostatFleetBridgeCommand.Generate =>
          given ActorSystem = ActorSystem("ThermostatFleetBridgeGenerate")
          given Materializer = Materializer.matFromSystem
          given ExecutionContext = summon[ActorSystem].dispatcher

          val outcome =
            try
              val generateF =
                if generate.mode == "mixed-mode" then
                  ThermostatFleetMixedModeDemoRunner.generateToFile(
                    outputPath            = generate.outputPath,
                    trialCount            = generate.trialCount,
                    parallelism           = generate.parallelism,
                    simulationTicks       = generate.simulationTicks,
                    initialProvisionedWcu = generate.initialProvisionedWcu,
                    adjustedProvisionedWcu = generate.adjustedProvisionedWcu
                  )
                else
                  ThermostatFleetDemoRunner.generateToFile(
                    outputPath      = generate.outputPath,
                    trialCount      = generate.trialCount,
                    parallelism     = generate.parallelism,
                    simulationTicks = generate.simulationTicks,
                    mode            = generate.mode
                  )
              val message = Await.result(generateF, 10.minutes)
              println(message)
              println(s"generated batch ${generate.batchId} (${generate.mode}) to ${generate.outputPath}")
              Success(())
            catch
              case t: Throwable => Failure(t)

          Await.result(summon[ActorSystem].terminate(), 30.seconds)
          outcome match
            case Success(_) => ()
            case Failure(t) =>
              System.err.println(s"generate failed: ${t.getMessage}")
              sys.exit(1)

        case stage: ThermostatFleetBridgeCommand.Stage =>
          try
            import org.json4s.*
            import org.json4s.jackson.JsonMethods.parse
            given Formats = DefaultFormats
            // Infer the scenarioId from the first JSONL record so --mode is not required for stage.
            val inferredScenarioId = Try {
              val lines = Files.lines(stage.inputPath)
              try
                val opt = lines.filter(s => s.trim.nonEmpty).findFirst()
                if opt.isPresent then (parse(opt.get()) \ "scenarioId").extract[String]
                else stage.metadata.scenarioId
              finally lines.close()
            }.getOrElse(stage.metadata.scenarioId)
            val metadata = stage.metadata.copy(scenarioId = inferredScenarioId)
            val count = OrderTrackingPostgresBridge.stage(
              inputPath = stage.inputPath,
              metadata = metadata,
              dbUrl = stage.dbUrl,
              dbUser = stage.dbUser,
              dbPassword = stage.dbPassword
            )
            println(s"staged $count records for batch ${metadata.batchId} into ${stage.dbUrl}")
          catch
            case t: Throwable =>
              System.err.println(s"stage failed: ${t.getMessage}")
              sys.exit(1)

        case view: ThermostatFleetBridgeCommand.View =>
          println(
            if view.mode == "mixed-mode" then
              ThermostatFleetMixedModeGrafanaView.url(
                grafanaBaseUrl = view.grafanaBaseUrl,
                batchId        = view.batchId,
                scenarioId     = view.scenarioId
              )
            else
              ThermostatFleetGrafanaView.url(
                grafanaBaseUrl = view.grafanaBaseUrl,
                batchId        = view.batchId,
                scenarioId     = view.scenarioId
              )
          )
