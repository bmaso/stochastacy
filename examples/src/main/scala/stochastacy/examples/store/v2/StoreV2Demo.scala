package stochastacy.examples.store.v2

import java.nio.file.{Files, Paths}

import scala.concurrent.Await
import scala.concurrent.duration.*

import org.apache.pekko.actor.ActorSystem
import stochastacy.core.sampler.LogNormalSampler
import stochastacy.examples.store.{ApiWorkloadConfig, StoreConfig, StoreReport}

/** The Store Demo V2 bridge: runs a Monte Carlo ensemble of the datastore behind a full gate stack
 *  (latency → throttle → chaos), writes the per-`(usecase, metric, window)` statistics to JSONL, and
 *  prints a summary of the per-gate outcome rates. Reuses the original demo's datastore, workload, and
 *  JSONL exporter; the gating is all interface-component gates.
 *
 *  Usage: `runMain stochastacy.examples.store.v2.StoreV2Demo --output /tmp/store-v2-demo.jsonl
 *  --trials 8 --ticks 200 --window 50 --seed 1`. */
@main def StoreV2Demo(args: String*): Unit =
  val opts        = parseArgs(args)
  val output      = opts.getOrElse("output", "/tmp/store-v2-demo.jsonl")
  val seed        = opts.get("seed").map(_.toLong).getOrElse(1L)
  val ticks       = opts.get("ticks").map(_.toLong).getOrElse(200L)
  val trials      = opts.get("trials").map(_.toInt).getOrElse(8)
  val window      = opts.get("window").map(_.toLong).getOrElse(50L)
  val parallelism = opts.get("parallelism").map(_.toInt).getOrElse(4)

  val api   = ApiWorkloadConfig.capstone
  val store = StoreConfig(initialEntities = 1_000L, createRate = 0.9, latencyPerEvaluatedItem = 5.0e-4)
  val edge  = EdgeConfig(
    latency          = LogNormalSampler.constant(mu = math.log(0.05), sigma = 0.4),
    rateLimiter      = RateLimiter.FlatThrottle(18),
    chaosProbability = 0.02
  )

  given system: ActorSystem = ActorSystem("store-v2-demo")
  try
    val result = Await.result(
      StoreV2MonteCarloRunner.run(api, store, edge, seed, ticks, trials, parallelism, requestTicks = -1L, windowTicks = window),
      5.minutes
    )
    Files.write(Paths.get(output), StoreReport.jsonl(result).getBytes("UTF-8"))
    print(StoreV2Report.summary(result))
    val records = StoreReport.pooledLines(result).size + StoreReport.acrossTrialLines(result).size
    println(s"[store-v2-demo] wrote $records JSONL records to $output")
  finally
    system.terminate()

private def parseArgs(args: Seq[String]): Map[String, String] =
  args.sliding(2, 2).collect { case Seq(k, v) if k.startsWith("--") => k.drop(2) -> v }.toMap
