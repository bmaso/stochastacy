package stochastacy.examples.ordertracking

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import stochastacy.demo.{DemoExportBundle, DemoJsonlExporter, DemoReportBuilder, FutureMultiTrialExecutor, TrialExecutionConfig}

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.format.DateTimeFormatter
import java.time.{ZoneOffset, ZonedDateTime}
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

final case class OrderTrackingPhase1DemoOptions(
                                                 outputPath: Option[Path],
                                                 trialCount: Int,
                                                 parallelism: Int,
                                                 simulationTicks: Long
                                               )

object OrderTrackingPhase1BridgeCli:
  private val GenerateUsage =
    "usage: OrderTrackingPhase1Bridge generate --output <path> [--batch-id <id>] [--trial-count <int>] [--parallelism <int>] [--simulation-ticks <long>]"
  private val StageUsage =
    "usage: OrderTrackingPhase1Bridge stage --input <path> --batch-id <id> --db-url <jdbc-url> --db-user <user> --db-password <password> --trial-count <int> --parallelism <int> --simulation-ticks <long> [--scenario-id <id>] [--read-consistency <value>] [--table-name <name>]"
  private val ViewUsage =
    "usage: OrderTrackingPhase1Bridge view --batch-id <id> [--scenario-id <id>] [--grafana-base-url <url>]"
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
    val defaults = OrderTrackingScenarioConfig.phase1Default

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
                  simulationTicks = simulationTicks.getOrElse(defaults.simulationTicks),
                  startEpochSeconds = OrderTrackingPhase2BridgeCli.DefaultStartEpochSeconds
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
    val defaults = OrderTrackingScenarioConfig.phase1Default

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
                baseSeed = OrderTrackingPhase1DemoRunner.Phase1BaseSeed,
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
    val defaults = OrderTrackingScenarioConfig.phase1Default

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
    s"order-tracking-phase1-${now.format(formatter)}"

object OrderTrackingPhase1DemoRunner:
  val Phase1BaseSeed: Long = 20260418L

  def run(
           options: OrderTrackingPhase1DemoOptions
         )(using ActorSystem, Materializer, ExecutionContext): Future[DemoExportBundle] =
    val scenarioConfig = OrderTrackingScenarioConfig.phase1Default.copy(
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
          baseSeed = Phase1BaseSeed
        )
      )
      .map(DemoReportBuilder.build)

  def emit(
            options: OrderTrackingPhase1DemoOptions,
            bundle: DemoExportBundle
          ): String =
    val rendered = DemoJsonlExporter.render(bundle.records)

    options.outputPath match
      case Some(path) =>
        DemoJsonlExporter.write(path, bundle.records)
        s"wrote ${bundle.records.size} records for scenario ${bundle.aggregate.scenarioId} to $path"

      case None =>
        rendered

object OrderTrackingPhase1GrafanaView:
  private val DashboardUid = "ips-phase1-order-tracking"
  private val DashboardSlug = "ips-phase-1-order-tracking-dynamodb-simulation"

  def url(grafanaBaseUrl: String, batchId: String, scenarioId: String): String =
    val base = grafanaBaseUrl.stripSuffix("/")
    s"$base/d/$DashboardUid/$DashboardSlug?var-batch_id=${encode(batchId)}&var-scenarioId=${encode(scenarioId)}"

  private def encode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)

@main def OrderTrackingPhase1Bridge(args: String*): Unit =
  OrderTrackingPhase1BridgeCli.parseArgs(args) match
    case Left(error) =>
      System.err.println(error)
      sys.exit(1)

    case Right(command) =>
      command match
        case generate: OrderTrackingBridgeCommand.Generate =>
          given ActorSystem = ActorSystem("OrderTrackingPhase1BridgeGenerate")
          given Materializer = Materializer.matFromSystem
          given ExecutionContext = summon[ActorSystem].dispatcher

          val outcome =
            try
              val options = OrderTrackingPhase1DemoOptions(
                outputPath = Some(generate.outputPath),
                trialCount = generate.trialCount,
                parallelism = generate.parallelism,
                simulationTicks = generate.simulationTicks
              )
              val bundle = Await.result(
                OrderTrackingPhase1DemoRunner.run(options),
                10.minutes
              )
              OrderTrackingPhase1DemoRunner.emit(options, bundle)
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
            OrderTrackingPhase1GrafanaView.url(
              grafanaBaseUrl = view.grafanaBaseUrl,
              batchId = view.batchId,
              scenarioId = view.scenarioId
            )
          )
