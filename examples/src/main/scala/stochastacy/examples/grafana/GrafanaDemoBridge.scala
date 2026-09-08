package stochastacy.examples.grafana

import java.nio.file.Path
import java.time.{ZoneOffset, ZonedDateTime}

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer

import stochastacy.aws.examples.demo.SingleTableScenario
import stochastacy.aws.examples.ordertracking.OrderTrackingConfig
import stochastacy.demo.{BatchMetadata, DemoPostgresStaging, GrafanaBridge}

/** One v2 demo the bridge can drive: how to build its (override-able) scenario, its batch-metadata bits, and
 *  the Grafana dashboard it lands on. */
final case class DemoSpec(
  name:            String,
  tableName:       String,
  readConsistency: String,
  dashboardUid:    String,
  dashboardSlug:   String,
  defaultTrials:   Int,
  defaultTicks:    Long,
  defaultParallelism: Int,
  scenarioFor:     (Int, Long, Int) => SingleTableScenario
)

/**
 * The shared v2 demo → Postgres/Grafana bridge CLI: `generate` runs a v2 demo's Monte Carlo ensemble and
 * writes staging JSONL (via [[GrafanaBridge]]), `stage` loads it into Postgres ([[DemoPostgresStaging]]), and
 * `view` prints the dashboard URL. One CLI for every wired demo — Slice 1 wires the two order-tracking demos,
 * reusing the legacy dashboards.
 *
 * {{{
 * generate --demo <name> --output <path> --batch-id <id> [--seed n] [--trials n] [--ticks n] [--parallelism n]
 * stage    --demo <name> --input  <path> --batch-id <id> --db-url <url> --db-user <u> --db-password <p> [--seed n] [--trials n] [--ticks n] [--parallelism n]
 * view     --demo <name> --batch-id <id> [--grafana-base-url <url>]
 * }}}
 */
object GrafanaDemoBridgeCli:

  val Demos: Map[String, DemoSpec] = Map(
    "order-tracking-phase1" -> DemoSpec(
      name = "order-tracking-phase1", tableName = "orders", readConsistency = "StronglyConsistent",
      dashboardUid = "ips-phase1-order-tracking", dashboardSlug = "ips-phase-1-order-tracking-dynamodb-simulation",
      defaultTrials = OrderTrackingConfig.phase1Default.trialCount,
      defaultTicks = OrderTrackingConfig.phase1Default.simulationTicks,
      defaultParallelism = OrderTrackingConfig.phase1Default.parallelism,
      scenarioFor = (tr, tk, p) => OrderTrackingConfig.phase1Default.copy(trialCount = tr, simulationTicks = tk, parallelism = p)
    ),
    "order-tracking-indexed" -> DemoSpec(
      name = "order-tracking-indexed", tableName = "orders", readConsistency = "StronglyConsistent",
      dashboardUid = "ips-phase2-order-tracking", dashboardSlug = "ips-phase-2-order-tracking-dynamodb-simulation",
      defaultTrials = OrderTrackingConfig.indexedDefault.trialCount,
      defaultTicks = OrderTrackingConfig.indexedDefault.simulationTicks,
      defaultParallelism = OrderTrackingConfig.indexedDefault.parallelism,
      scenarioFor = (tr, tk, p) => OrderTrackingConfig.indexedDefault.copy(trialCount = tr, simulationTicks = tk, parallelism = p)
    )
  )

@main def GrafanaDemoBridge(args: String*): Unit =
  def flag(name: String): Option[String] = args.sliding(2).collectFirst { case Seq(k, v) if k == s"--$name" => v }
  def required(name: String): String = flag(name).getOrElse(sys.error(s"missing required flag --$name"))

  val command = args.headOption.getOrElse(sys.error("usage: <generate|stage|view> --demo <name> ..."))
  val demoName = required("demo")
  val spec = GrafanaDemoBridgeCli.Demos.getOrElse(demoName,
    sys.error(s"unknown --demo '$demoName'; known: ${GrafanaDemoBridgeCli.Demos.keys.toVector.sorted.mkString(", ")}"))

  val trials      = flag("trials").flatMap(_.toIntOption).getOrElse(spec.defaultTrials)
  val ticks       = flag("ticks").flatMap(_.toLongOption).getOrElse(spec.defaultTicks)
  val parallelism = flag("parallelism").flatMap(_.toIntOption).getOrElse(spec.defaultParallelism)
  val seed        = flag("seed").flatMap(_.toLongOption).getOrElse(1L)
  val scenario    = spec.scenarioFor(trials, ticks, parallelism)

  command match
    case "generate" =>
      val output = Path.of(required("output"))
      given system: ActorSystem = ActorSystem("GrafanaDemoBridge")
      given Materializer        = Materializer.matFromSystem
      given ExecutionContext    = system.dispatcher
      try
        val offset = ZonedDateTime.now(ZoneOffset.UTC).toEpochSecond
        val count  = Await.result(GrafanaBridge.generateSingleTable(scenario, seed, output, offset), 60.minutes)
        println(s"generated $count records for '${scenario.scenarioId}' → $output")
      finally Await.result(system.terminate(), 30.seconds)

    case "stage" =>
      val input = Path.of(required("input"))
      val metadata = BatchMetadata(
        batchId = required("batch-id"), scenarioId = scenario.scenarioId, trialCount = trials,
        parallelism = parallelism, simulationTicks = ticks, baseSeed = seed,
        readConsistency = spec.readConsistency, tableName = spec.tableName, sourceJsonlPath = Some(input.toString))
      val count = DemoPostgresStaging.stage(input, metadata, required("db-url"), required("db-user"), required("db-password"))
      println(s"staged $count records for batch '${metadata.batchId}'")

    case "view" =>
      val url = GrafanaBridge.viewUrl(
        flag("grafana-base-url").getOrElse("http://localhost:3000"),
        spec.dashboardUid, spec.dashboardSlug, required("batch-id"), scenario.scenarioId)
      println(url)

    case other => sys.error(s"unknown command '$other'; use generate | stage | view")
