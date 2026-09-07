package stochastacy.aws.examples.hotreplica

import java.nio.file.{Files, Path}

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer

/**
 * Runnable hot-replica demo: a thermostat-flavored 3-region Global Table run as two Monte Carlo arms —
 *
 *   - **reconcile** — all on-demand, legacy fleets 1800 / 900 / 300; replication stays healthy (pending ≈ 0,
 *     latency ≈ the link-lag mean);
 *   - **depletion** — an 8 : 1 fleet discrepancy (2000 / 250 / 300) whose provisioned `ap-southeast-1` replica
 *     has an inbound rWCU ceiling below its combined inbound, so **both** its inbound links back up and
 *     **diverge**: the heavy `us-east-1` stream builds the deeper, slower queue (pending and latency well
 *     above the light `eu-west-1` stream). Per-tick pending + latency for the depletion arm are streamed to JSONL.
 *
 * The console summary reports per-region capacity/cost (incl. rWCU) and the per-link replication metrics,
 * making the per-link distinction legible. No external services.
 *
 * Flags (all optional): `--output <path>` `--seed <long>` `--trials <int>` `--ticks <long>` `--parallelism <int>`.
 */
@main def HotReplicaDemo(args: String*): Unit =
  def flag(name: String): Option[String] =
    args.grouped(2).collectFirst { case Seq(k, v) if k == s"--$name" => v }

  val output = flag("output").map(Path.of(_)).getOrElse(Path.of("/tmp/hot-replica.jsonl"))
  val seed   = flag("seed").flatMap(_.toLongOption).getOrElse(1L)
  val trials = flag("trials").flatMap(_.toIntOption).getOrElse(50)
  val ticks  = flag("ticks").flatMap(_.toLongOption).getOrElse(300L)
  val par    = flag("parallelism").flatMap(_.toIntOption).getOrElse(8)

  val reconcile = HotReplicaConfig.reconcileDefault(ticks, trials, par)
  val depletion = HotReplicaConfig.depletionDefault(ticks, trials, par)

  given system: ActorSystem = ActorSystem("HotReplicaDemo")
  given Materializer        = Materializer.matFromSystem
  given ExecutionContext    = system.dispatcher
  try
    val runner    = new HotReplicaMonteCarloRunner()
    val recResult = Await.result(runner.run(reconcile, seed), 30.minutes)
    val depResult = Await.result(runner.run(depletion, seed), 30.minutes)

    writeDepletionJsonl(output, depResult)

    println(renderArm("reconcile (on-demand, 1800/900/300)", recResult))
    println()
    println(renderArm("depletion (8:1, 2000/250/300; ap-southeast rWCU-capped)", depResult))
    println()
    println(renderDivergence(depResult))
    println(s"  wrote per-tick pending/latency rows for the depletion arm to $output")
  finally
    Await.result(system.terminate(), 30.seconds)

private def money(v: BigDecimal): String = "$" + v.setScale(4, BigDecimal.RoundingMode.HALF_UP).toString
private def num(v: Double, dp: Int = 1): String = BigDecimal(v).setScale(dp, BigDecimal.RoundingMode.HALF_UP).toString

private def renderArm(title: String, r: HotReplicaResult): String =
  val regionLines = r.regions.map { g =>
    f"    ${g.regionName}%-16s rcu=${num(g.meanRcu, 0)}%-10s wcu=${num(g.meanWcu, 0)}%-10s rwcu=${num(g.meanRwcu, 0)}%-10s throttled=${num(g.meanThrottledRequests, 0)}%-8s cost=${money(g.meanTotalCost)}"
  }.mkString("\n")
  val linkLines = r.links.map { l =>
    f"    ${l.sourceRegion}%-14s -> ${l.destRegion}%-14s pending(mean/max)=${num(l.meanPendingMean)}/${num(l.meanPendingMax, 0)}   latency(mean/p95/max)=${num(l.meanLatencyMean)}/${num(l.meanLatencyP95)}/${num(l.meanLatencyMax)}"
  }.mkString("\n")
  s"""$title — ${r.trialCount} trials
     |  per region:
     |$regionLines
     |  per link:
     |$linkLines""".stripMargin

private def renderDivergence(r: HotReplicaResult): String =
  val heavy = r.links.find(l => l.sourceRegion == HotReplicaConfig.UsEast && l.destRegion == HotReplicaConfig.ApSoutheast)
  val light = r.links.find(l => l.sourceRegion == HotReplicaConfig.EuWest && l.destRegion == HotReplicaConfig.ApSoutheast)
  (heavy, light) match
    case (Some(h), Some(l)) =>
      val pendRatio = if l.meanPendingMean <= 0.0 then Double.PositiveInfinity else h.meanPendingMean / l.meanPendingMean
      s"""  per-link divergence into ap-southeast-1 (the rWCU-capped replica):
         |    us-east-1  (heavy) pending=${num(h.meanPendingMean)}  latency mean/max=${num(h.meanLatencyMean)}/${num(h.meanLatencyMax)}
         |    eu-west-1  (light) pending=${num(l.meanPendingMean)}  latency mean/max=${num(l.meanLatencyMean)}/${num(l.meanLatencyMax)}
         |    heavy/light pending ratio: ${if pendRatio.isInfinite then "inf" else num(pendRatio)}x""".stripMargin
    case _ => "  (per-link divergence unavailable)"

/** Stream the depletion arm's first-trial per-tick pending + mean latency, one JSON object per (tick, link). */
private def writeDepletionJsonl(output: Path, r: HotReplicaResult): Unit =
  val sb = new StringBuilder
  for l <- r.sampleLinks do
    val latencyByTick = l.perTickLatency.toMap
    for (tick, pending) <- l.perTickPending do
      val lat = latencyByTick.getOrElse(tick, 0.0)
      sb.append(s"""{"scenarioId":"${r.scenarioId}","tick":$tick,"sourceRegion":"${l.sourceRegion}",""")
        .append(s""""destRegion":"${l.destRegion}","pending":$pending,"latencyMean":${num(lat, 3)}}""")
        .append('\n')
  Option(output.getParent).foreach(Files.createDirectories(_))
  Files.writeString(output, sb.toString)
