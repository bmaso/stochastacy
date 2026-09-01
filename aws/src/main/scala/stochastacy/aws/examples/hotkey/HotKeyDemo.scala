package stochastacy.aws.examples.hotkey

import java.nio.file.Path

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer

/**
 * Runnable hot-key demo: the `HotKeyConfig.default` scenario run as three Monte Carlo arms on the identical
 * workload, to make phase-10's spatial-capacity model legible by contrast —
 *
 *   - **hot, adaptive on** (the realistic DynamoDB default; per-tick JSONL written) — a hot partition is
 *     relieved to the physical max and split-for-heat grows the topology;
 *   - **hot, adaptive off** (the fair-share baseline) — the same hot partition throttles harder;
 *   - **well-distributed control** (`hotFraction ≈ 0`) — no per-partition hotspot, so throttling all but
 *     vanishes.
 *
 * The console summary reports each arm's throttled-request count and the hot arm's final partition count
 * (base + heat-splits). No external services.
 *
 * Flags (all optional): `--output <path>` `--seed <long>` `--trials <int>` `--ticks <long>`
 * `--parallelism <int>`.
 */
@main def HotKeyDemo(args: String*): Unit =
  def flag(name: String): Option[String] =
    args.grouped(2).collectFirst { case Seq(k, v) if k == s"--$name" => v }

  val output = flag("output").map(Path.of(_)).getOrElse(Path.of("/tmp/hot-key.jsonl"))
  val seed   = flag("seed").flatMap(_.toLongOption).getOrElse(1L)

  val base = HotKeyConfig.default.copy(
    trialCount      = flag("trials").flatMap(_.toIntOption).getOrElse(HotKeyConfig.default.trialCount),
    simulationTicks = flag("ticks").flatMap(_.toLongOption).getOrElse(HotKeyConfig.default.simulationTicks),
    parallelism     = flag("parallelism").flatMap(_.toIntOption).getOrElse(HotKeyConfig.default.parallelism)
  )
  val hotAdaptive = base.copy(scenarioId = "hot-key-adaptive-on")
  // Split-for-heat requires adaptive capacity (growing the count only relieves under the physical-max
  // ceiling), so the fair-share arm clears the policy too.
  val hotFairShare = base.copy(scenarioId = "hot-key-adaptive-off", adaptiveCapacity = false, heatSplitPolicy = None)
  val distributed  = base.copy(scenarioId = "hot-key-distributed", hotFraction = 0.0)

  given system: ActorSystem = ActorSystem("HotKeyDemo")
  given Materializer        = Materializer.matFromSystem
  given ExecutionContext    = system.dispatcher
  try
    val runner    = new HotKeyMonteCarloRunner()
    val onResult  = Await.result(runner.runToFile("adaptive-on", hotAdaptive, seed, output), 30.minutes)
    val offResult = Await.result(runner.run("adaptive-off", hotFairShare, seed), 30.minutes)
    val ctlResult = Await.result(runner.run("distributed", distributed, seed), 30.minutes)

    def pct(v: BigDecimal): String = (v * 100).setScale(1, BigDecimal.RoundingMode.HALF_UP).toString + "%"
    def relief: String =
      if offResult.meanTotalThrottled == 0 then "n/a"
      else pct((offResult.meanTotalThrottled - onResult.meanTotalThrottled) / offResult.meanTotalThrottled)

    println(
      s"""Hot-key — Monte Carlo summary (${onResult.trialCount} trials, ${base.simulationTicks} ticks, base partitions ${base.basePartitionCount})
         |  arm                 mean offered   mean throttled   throttle rate
         |  hot, adaptive on    ${onResult.meanTotalOffered.setScale(0, BigDecimal.RoundingMode.HALF_UP)}          ${onResult.meanTotalThrottled.setScale(0, BigDecimal.RoundingMode.HALF_UP)}            ${pct(onResult.throttleRate)}
         |  hot, adaptive off   ${offResult.meanTotalOffered.setScale(0, BigDecimal.RoundingMode.HALF_UP)}          ${offResult.meanTotalThrottled.setScale(0, BigDecimal.RoundingMode.HALF_UP)}            ${pct(offResult.throttleRate)}
         |  well-distributed    ${ctlResult.meanTotalOffered.setScale(0, BigDecimal.RoundingMode.HALF_UP)}          ${ctlResult.meanTotalThrottled.setScale(0, BigDecimal.RoundingMode.HALF_UP)}            ${pct(ctlResult.throttleRate)}
         |  adaptive relief (throttles avoided on→off): $relief
         |  hot arm final partition count (base + heat-splits): ${onResult.meanFinalPartitionCount.setScale(2, BigDecimal.RoundingMode.HALF_UP)}
         |  wrote ${base.simulationTicks} per-tick rows for the adaptive-on arm to $output""".stripMargin)
  finally
    Await.result(system.terminate(), 30.seconds)
