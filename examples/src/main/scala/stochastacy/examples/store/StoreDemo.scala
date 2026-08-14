package stochastacy.examples.store

import java.nio.file.{Files, Paths}

import scala.concurrent.Await
import scala.concurrent.duration.*

import org.apache.pekko.actor.ActorSystem

/** The store-simulator capstone bridge: runs a Monte Carlo ensemble of the capstone workload, writes
 *  the per-`(usecase, metric, window)` statistics to JSONL, and prints a human summary of the three
 *  emergent phenomena. Declares the phase-0 store simulator complete; the full Grafana/Postgres
 *  pipeline stays deferred.
 *
 *  Usage: `runMain stochastacy.examples.store.StoreDemo --output /tmp/store-demo.jsonl --trials 8
 *  --ticks 200 --window 50 --seed 1`. The capstone uses a small `initialEntities` with sustained
 *  creates so the write-driven cardinality rise is visible across windows. */
@main def StoreDemo(args: String*): Unit =
  val opts        = parseArgs(args)
  val output      = opts.getOrElse("output", "/tmp/store-demo.jsonl")
  val seed        = opts.get("seed").map(_.toLong).getOrElse(1L)
  val ticks       = opts.get("ticks").map(_.toLong).getOrElse(200L)
  val trials      = opts.get("trials").map(_.toInt).getOrElse(8)
  val window      = opts.get("window").map(_.toLong).getOrElse(50L)
  val parallelism = opts.get("parallelism").map(_.toInt).getOrElse(4)

  val api   = ApiWorkloadConfig.capstone
  val store = StoreConfig(initialEntities = 1_000L, createRate = 0.9, latencyPerEvaluatedItem = 5.0e-4)
  val svc   = ServiceConfig()
  val adm   = AdmissionConfig(capacityPerTick = 18)

  given system: ActorSystem = ActorSystem("store-demo")
  try
    val result = Await.result(
      StoreMonteCarloRunner.run(api, store, svc, seed, ticks, trials, adm, parallelism, requestTicks = -1L, windowTicks = window),
      5.minutes
    )
    val jsonl = StoreReport.jsonl(result)
    Files.write(Paths.get(output), jsonl.getBytes("UTF-8"))
    print(StoreReport.summary(result))
    val records = StoreReport.pooledLines(result).size + StoreReport.acrossTrialLines(result).size
    println(s"[store-demo] wrote $records JSONL records to $output")
  finally
    system.terminate()

/** Minimal `--key value` argument parser. */
private def parseArgs(args: Seq[String]): Map[String, String] =
  args.sliding(2, 2).collect { case Seq(k, v) if k.startsWith("--") => k.drop(2) -> v }.toMap
