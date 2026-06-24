package stochastacy.examples.eas

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import stochastacy.examples.ordertracking.{BatchMetadata, OrderTrackingPostgresBridge}

import java.nio.file.Path
import java.time.{ZoneOffset, ZonedDateTime}
import java.time.format.DateTimeFormatter
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}
import scala.util.{Failure, Success, Try}

// ── Command ADT ──────────────────────────────────────────────────────────────

sealed trait EasBridgeCommand

object EasBridgeCommand:

  final case class Generate(
    batchId:         String,
    outputPath:      String,
    burstMultiplier: Double,
    trialCount:      Int,
    parallelism:     Int,
    simulationTicks: Long,
    maxWaitMinutes:  Int = 60
  ) extends EasBridgeCommand

  final case class Stage(
    inputPath:       Path,
    batchId:         String,
    trialCount:      Int,
    parallelism:     Int,
    simulationTicks: Long,
    dbUrl:           String,
    dbUser:          String,
    dbPassword:      String
  ) extends EasBridgeCommand

  final case class View(
    batchId: String
  ) extends EasBridgeCommand

// ── CLI parser ───────────────────────────────────────────────────────────────

object EasBridgeCli:

  private val defaults = EasScenarioConfig.default

  private val GenerateUsage =
    "usage: EasBridge generate --output <path> [--batch-id <id>] [--burst-multiplier <double>] [--trial-count <int>] [--parallelism <int>] [--simulation-ticks <long>]"
  private val StageUsage =
    "usage: EasBridge stage --input <path> --batch-id <id> --db-url <jdbc-url> --db-user <user> --db-password <password> --trial-count <int> --parallelism <int> --simulation-ticks <long>"
  private val ViewUsage =
    "usage: EasBridge view --batch-id <id>"
  private val TopLevelUsage =
    s"""usage:
       |  $GenerateUsage
       |  $StageUsage
       |  $ViewUsage""".stripMargin

  def parseArgs(
    args: Seq[String],
    now:  ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC)
  ): Either[String, EasBridgeCommand] =
    args.toList match
      case "generate" :: tail => parseGenerate(tail, now)
      case "stage"    :: tail => parseStage(tail)
      case "view"     :: tail => parseView(tail)
      case Nil                => Left(TopLevelUsage)
      case sub :: _           => Left(s"unknown subcommand: $sub\n$TopLevelUsage")

  // ── generate ────────────────────────────────────────────────────────────────

  private def parseGenerate(
    args: List[String],
    now:  ZonedDateTime
  ): Either[String, EasBridgeCommand.Generate] =

    def loop(
      remaining:       List[String],
      outputPath:      Option[String],
      batchId:         Option[String],
      burstMultiplier: Option[Double],
      trialCount:      Option[Int],
      parallelism:     Option[Int],
      simulationTicks: Option[Long]
    ): Either[String, EasBridgeCommand.Generate] =
      remaining match
        case Nil =>
          outputPath.toRight(s"missing required flag: --output\n$GenerateUsage").map { path =>
            EasBridgeCommand.Generate(
              batchId         = batchId.getOrElse(defaultBatchId(now)),
              outputPath      = path,
              burstMultiplier = burstMultiplier.getOrElse(defaults.burstMultiplier),
              trialCount      = trialCount.getOrElse(100),
              parallelism     = parallelism.getOrElse(8),
              simulationTicks = simulationTicks.getOrElse(defaults.simulationTicks)
            )
          }

        case "--output"           :: v :: t => loop(t, Some(v), batchId, burstMultiplier, trialCount, parallelism, simulationTicks)
        case "--batch-id"         :: v :: t => loop(t, outputPath, Some(v), burstMultiplier, trialCount, parallelism, simulationTicks)
        case "--burst-multiplier" :: v :: t =>
          parseDoubleFlag("--burst-multiplier", v, GenerateUsage).flatMap(d =>
            loop(t, outputPath, batchId, Some(d), trialCount, parallelism, simulationTicks))
        case "--trial-count"      :: v :: t =>
          parseIntFlag("--trial-count", v, GenerateUsage).flatMap(i =>
            loop(t, outputPath, batchId, burstMultiplier, Some(i), parallelism, simulationTicks))
        case "--parallelism"      :: v :: t =>
          parseIntFlag("--parallelism", v, GenerateUsage).flatMap(i =>
            loop(t, outputPath, batchId, burstMultiplier, trialCount, Some(i), simulationTicks))
        case "--simulation-ticks" :: v :: t =>
          parseLongFlag("--simulation-ticks", v, GenerateUsage).flatMap(l =>
            loop(t, outputPath, batchId, burstMultiplier, trialCount, parallelism, Some(l)))

        case flag :: _ if flag.startsWith("--") => Left(s"unknown or malformed flag: $flag\n$GenerateUsage")
        case value :: _                          => Left(s"unexpected argument: $value\n$GenerateUsage")

    loop(args, None, None, None, None, None, None)

  // ── stage ────────────────────────────────────────────────────────────────────

  private def parseStage(args: List[String]): Either[String, EasBridgeCommand.Stage] =

    def loop(
      remaining:       List[String],
      inputPath:       Option[Path],
      batchId:         Option[String],
      dbUrl:           Option[String],
      dbUser:          Option[String],
      dbPassword:      Option[String],
      trialCount:      Option[Int],
      parallelism:     Option[Int],
      simulationTicks: Option[Long]
    ): Either[String, EasBridgeCommand.Stage] =
      remaining match
        case Nil =>
          for
            path     <- inputPath.toRight(s"missing required flag: --input\n$StageUsage")
            id       <- batchId.toRight(s"missing required flag: --batch-id\n$StageUsage")
            url      <- dbUrl.toRight(s"missing required flag: --db-url\n$StageUsage")
            user     <- dbUser.toRight(s"missing required flag: --db-user\n$StageUsage")
            password <- dbPassword.toRight(s"missing required flag: --db-password\n$StageUsage")
            tc       <- trialCount.toRight(s"missing required flag: --trial-count\n$StageUsage")
            p        <- parallelism.toRight(s"missing required flag: --parallelism\n$StageUsage")
            ticks    <- simulationTicks.toRight(s"missing required flag: --simulation-ticks\n$StageUsage")
          yield
            EasBridgeCommand.Stage(path, id, tc, p, ticks, url, user, password)

        case "--input"            :: v :: t => loop(t, Some(Path.of(v)), batchId, dbUrl, dbUser, dbPassword, trialCount, parallelism, simulationTicks)
        case "--batch-id"         :: v :: t => loop(t, inputPath, Some(v), dbUrl, dbUser, dbPassword, trialCount, parallelism, simulationTicks)
        case "--db-url"           :: v :: t => loop(t, inputPath, batchId, Some(v), dbUser, dbPassword, trialCount, parallelism, simulationTicks)
        case "--db-user"          :: v :: t => loop(t, inputPath, batchId, dbUrl, Some(v), dbPassword, trialCount, parallelism, simulationTicks)
        case "--db-password"      :: v :: t => loop(t, inputPath, batchId, dbUrl, dbUser, Some(v), trialCount, parallelism, simulationTicks)
        case "--trial-count"      :: v :: t =>
          parseIntFlag("--trial-count", v, StageUsage).flatMap(i =>
            loop(t, inputPath, batchId, dbUrl, dbUser, dbPassword, Some(i), parallelism, simulationTicks))
        case "--parallelism"      :: v :: t =>
          parseIntFlag("--parallelism", v, StageUsage).flatMap(i =>
            loop(t, inputPath, batchId, dbUrl, dbUser, dbPassword, trialCount, Some(i), simulationTicks))
        case "--simulation-ticks" :: v :: t =>
          parseLongFlag("--simulation-ticks", v, StageUsage).flatMap(l =>
            loop(t, inputPath, batchId, dbUrl, dbUser, dbPassword, trialCount, parallelism, Some(l)))

        case flag :: _ if flag.startsWith("--") => Left(s"unknown or malformed flag: $flag\n$StageUsage")
        case value :: _                          => Left(s"unexpected argument: $value\n$StageUsage")

    loop(args, None, None, None, None, None, None, None, None)

  // ── view ─────────────────────────────────────────────────────────────────────

  private def parseView(args: List[String]): Either[String, EasBridgeCommand.View] =
    def loop(remaining: List[String], batchId: Option[String]): Either[String, EasBridgeCommand.View] =
      remaining match
        case Nil              => batchId.map(EasBridgeCommand.View.apply).toRight(s"missing required flag: --batch-id\n$ViewUsage")
        case "--batch-id" :: v :: t => loop(t, Some(v))
        case flag :: _ if flag.startsWith("--") => Left(s"unknown or malformed flag: $flag\n$ViewUsage")
        case value :: _       => Left(s"unexpected argument: $value\n$ViewUsage")
    loop(args, None)

  // ── helpers ──────────────────────────────────────────────────────────────────

  private def defaultBatchId(now: ZonedDateTime): String =
    "eas-" + now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))

  private def parseIntFlag(flag: String, value: String, usage: String): Either[String, Int] =
    Try(value.toInt).toEither.left.map(_ => s"$flag must be an integer, got: $value\n$usage")

  private def parseLongFlag(flag: String, value: String, usage: String): Either[String, Long] =
    Try(value.toLong).toEither.left.map(_ => s"$flag must be a long integer, got: $value\n$usage")

  private def parseDoubleFlag(flag: String, value: String, usage: String): Either[String, Double] =
    Try(value.toDouble).toEither.left.map(_ => s"$flag must be a decimal number, got: $value\n$usage")

// ── Entry point ───────────────────────────────────────────────────────────────

@main def EasBridge(args: String*): Unit =
  EasBridgeCli.parseArgs(args) match
    case Left(error) =>
      System.err.println(error)
      sys.exit(1)

    case Right(command) =>
      command match

        // ── generate ──────────────────────────────────────────────────────────
        case gen: EasBridgeCommand.Generate =>
          given ActorSystem    = ActorSystem("EasBridgeGenerate")
          given Materializer   = Materializer.matFromSystem
          given ExecutionContext = summon[ActorSystem].dispatcher

          val config = EasScenarioConfig(
            burstMultiplier = gen.burstMultiplier,
            simulationTicks = gen.simulationTicks
          )

          val outcome = Try {
            val message = Await.result(
              EasDemoRunner.generateToFile(
                config      = config,
                outputPath  = gen.outputPath,
                trialCount  = gen.trialCount,
                parallelism = gen.parallelism
              ),
              gen.maxWaitMinutes.minutes
            )
            println(message)
            println(s"generated batch ${gen.batchId} to ${gen.outputPath}")
          }
          Await.result(summon[ActorSystem].terminate(), 30.seconds)
          outcome match
            case Success(_) => ()
            case Failure(t) =>
              System.err.println(s"generate failed: ${t.getMessage}")
              sys.exit(1)

        // ── stage ─────────────────────────────────────────────────────────────
        case stage: EasBridgeCommand.Stage =>
          val metadata = BatchMetadata(
            batchId          = stage.batchId,
            scenarioId       = EasScenarioConfig.default.scenarioId,
            trialCount       = stage.trialCount,
            parallelism      = stage.parallelism,
            simulationTicks  = stage.simulationTicks,
            baseSeed         = EasScenarioConfig.BaseSeed,
            readConsistency  = "eventually-consistent",
            tableName        = "alerts",
            sourceJsonlPath  = Some(stage.inputPath.toString)
          )
          Try(OrderTrackingPostgresBridge.stage(stage.inputPath, metadata, stage.dbUrl, stage.dbUser, stage.dbPassword)) match
            case Success(count) =>
              println(s"staged $count records for batch ${stage.batchId}")
            case Failure(t) =>
              System.err.println(s"stage failed: ${t.getMessage}")
              sys.exit(1)

        // ── view ──────────────────────────────────────────────────────────────
        case view: EasBridgeCommand.View =>
          println(
            s"""EAS burst scenario — batch ${view.batchId}
               |No Grafana dashboard is configured yet for this scenario.
               |Run 'stage' first, then connect Grafana to the stochastacy_demo schema.""".stripMargin
          )
