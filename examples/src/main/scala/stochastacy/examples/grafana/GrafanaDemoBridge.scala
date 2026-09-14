package stochastacy.examples.grafana

import java.nio.file.Path
import java.time.{ZoneOffset, ZonedDateTime}

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer

import stochastacy.aws.examples.demo.{MultiTableScenario, SingleTableScenario}
import stochastacy.aws.examples.ordertracking.OrderTrackingConfig
import stochastacy.aws.examples.thermostatfleet.{ThermostatConfig, ThermostatMultiTableConfig}
import stochastacy.demo.{BatchMetadata, DemoPostgresStaging, GrafanaBridge}

/** How the bridge builds a demo's (override-able) scenario — a single [[SingleTableScenario]] or a composed
 *  [[MultiTableScenario]]. `generate` dispatches on the kind; `stage`/`view` are kind-agnostic (both scenario
 *  types expose `scenarioId`). */
enum DemoKind:
  case Single(scenarioFor: (Int, Long, Int) => SingleTableScenario)
  case Multi (scenarioFor: (Int, Long, Int) => MultiTableScenario)

/** One v2 demo the bridge can drive: how to build its scenario, its batch-metadata bits, and the Grafana
 *  dashboard it lands on. `tableName` is the informational `demo_batches` label (a composite for multi-table). */
final case class DemoSpec(
  name:            String,
  tableName:       String,
  readConsistency: String,
  dashboardUid:    String,
  dashboardSlug:   String,
  defaultTrials:   Int,
  defaultTicks:    Long,
  defaultParallelism: Int,
  kind:            DemoKind
)

/**
 * The shared v2 demo → Postgres/Grafana bridge CLI: `generate` runs a v2 demo's Monte Carlo ensemble and
 * writes staging JSONL (via [[GrafanaBridge]]), `stage` loads it into Postgres ([[DemoPostgresStaging]]), and
 * `view` prints the dashboard URL. One CLI for every wired demo — the two order-tracking demos, the two
 * single-region / mixed-mode thermostat demos, and the two multi-table thermostat demos — each with its own
 * dashboard.
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
      kind = DemoKind.Single((tr, tk, p) => OrderTrackingConfig.phase1Default.copy(trialCount = tr, simulationTicks = tk, parallelism = p))
    ),
    "order-tracking-indexed" -> DemoSpec(
      name = "order-tracking-indexed", tableName = "orders", readConsistency = "StronglyConsistent",
      dashboardUid = "ips-phase2-order-tracking", dashboardSlug = "ips-phase-2-order-tracking-dynamodb-simulation",
      defaultTrials = OrderTrackingConfig.indexedDefault.trialCount,
      defaultTicks = OrderTrackingConfig.indexedDefault.simulationTicks,
      defaultParallelism = OrderTrackingConfig.indexedDefault.parallelism,
      kind = DemoKind.Single((tr, tk, p) => OrderTrackingConfig.indexedDefault.copy(trialCount = tr, simulationTicks = tk, parallelism = p))
    ),
    "thermostat-fleet" -> DemoSpec(
      name = "thermostat-fleet", tableName = "device-telemetry", readConsistency = "EventuallyConsistent",
      dashboardUid = "ips-phase3-thermostat-fleet", dashboardSlug = "ips-phase-3-thermostat-fleet-dynamodb-simulation",
      defaultTrials = ThermostatConfig.singleRegionDefault.trialCount,
      defaultTicks = ThermostatConfig.singleRegionDefault.simulationTicks,
      defaultParallelism = ThermostatConfig.singleRegionDefault.parallelism,
      kind = DemoKind.Single((tr, tk, p) => ThermostatConfig.singleRegionDefault.copy(trialCount = tr, simulationTicks = tk, parallelism = p))
    ),
    "thermostat-mixed-mode" -> DemoSpec(
      name = "thermostat-mixed-mode", tableName = "device-telemetry", readConsistency = "EventuallyConsistent",
      dashboardUid = "ips-phase4-mixed-mode", dashboardSlug = "thermostat-fleet-mixed-billing-mode-demo",
      defaultTrials = ThermostatConfig.mixedModeDefault.trialCount,
      defaultTicks = ThermostatConfig.mixedModeDefault.simulationTicks,
      defaultParallelism = ThermostatConfig.mixedModeDefault.parallelism,
      kind = DemoKind.Single((tr, tk, p) => ThermostatConfig.mixedModeDefault.copy(trialCount = tr, simulationTicks = tk, parallelism = p))
    ),
    "thermostat-fleet-multi-table" -> DemoSpec(
      name = "thermostat-fleet-multi-table", tableName = "device-registry,device-telemetry", readConsistency = "EventuallyConsistent",
      dashboardUid = "ips-phase6-multi-table", dashboardSlug = "thermostat-fleet-multi-table-demo",
      defaultTrials = ThermostatMultiTableConfig.twoTableDefault.trialCount,
      defaultTicks = ThermostatMultiTableConfig.twoTableDefault.simulationTicks,
      defaultParallelism = ThermostatMultiTableConfig.twoTableDefault.parallelism,
      kind = DemoKind.Multi((tr, tk, p) => ThermostatMultiTableConfig.twoTableDefault.withEnsemble(tr, tk, p))
    ),
    "thermostat-fleet-capstone" -> DemoSpec(
      name = "thermostat-fleet-capstone", tableName = "device-telemetry", readConsistency = "EventuallyConsistent",
      dashboardUid = "ips-phase6-capstone", dashboardSlug = "thermostat-fleet-capstone-demo",
      defaultTrials = ThermostatMultiTableConfig.capstone().trialCount,
      defaultTicks = ThermostatMultiTableConfig.capstone().simulationTicks,
      defaultParallelism = ThermostatMultiTableConfig.capstone().parallelism,
      kind = DemoKind.Multi((tr, tk, p) => ThermostatMultiTableConfig.capstone().withEnsemble(tr, tk, p))
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
  val scenarioId  = spec.kind match
    case DemoKind.Single(f) => f(trials, ticks, parallelism).scenarioId
    case DemoKind.Multi(f)  => f(trials, ticks, parallelism).scenarioId

  command match
    case "generate" =>
      val output = Path.of(required("output"))
      given system: ActorSystem = ActorSystem("GrafanaDemoBridge")
      given Materializer        = Materializer.matFromSystem
      given ExecutionContext    = system.dispatcher
      try
        val offset = ZonedDateTime.now(ZoneOffset.UTC).toEpochSecond
        val count  = spec.kind match
          case DemoKind.Single(f) => Await.result(GrafanaBridge.generateSingleTable(f(trials, ticks, parallelism), seed, output, offset), 60.minutes)
          case DemoKind.Multi(f)  => Await.result(GrafanaBridge.generateMultiTable(f(trials, ticks, parallelism), seed, output, offset), 60.minutes)
        println(s"generated $count records for '$scenarioId' → $output")
      finally Await.result(system.terminate(), 30.seconds)

    case "stage" =>
      val input = Path.of(required("input"))
      val metadata = BatchMetadata(
        batchId = required("batch-id"), scenarioId = scenarioId, trialCount = trials,
        parallelism = parallelism, simulationTicks = ticks, baseSeed = seed,
        readConsistency = spec.readConsistency, tableName = spec.tableName, sourceJsonlPath = Some(input.toString))
      val count = DemoPostgresStaging.stage(input, metadata, required("db-url"), required("db-user"), required("db-password"))
      println(s"staged $count records for batch '${metadata.batchId}'")

    case "view" =>
      val url = GrafanaBridge.viewUrl(
        flag("grafana-base-url").getOrElse("http://localhost:3000"),
        spec.dashboardUid, spec.dashboardSlug, required("batch-id"), scenarioId)
      println(url)

    case other => sys.error(s"unknown command '$other'; use generate | stage | view")
