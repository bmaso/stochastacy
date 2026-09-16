package stochastacy.examples.mm1

import java.nio.file.{Files, Paths}

import scala.concurrent.Await
import scala.concurrent.duration.*

import org.apache.pekko.actor.ActorSystem

/**
 * The MM1 demo: a paginated client against one FIFO server — M/M/1 with Bernoulli feedback — run as a circuit, where
 * the request/response loop closes **inside** a tick. Runs two arms (next page immediately, and after an exponential
 * think time), printing each metric beside its closed form and writing one JSONL line per trial.
 *
 * Usage: `runMain stochastacy.examples.mm1.MM1Demo --output /tmp/mm1.jsonl --trials 200 --ticks 300 --seed 1`.
 */
@main def MM1Demo(args: String*): Unit =
  val opts        = args.sliding(2, 2).collect { case Seq(k, v) if k.startsWith("--") => k.drop(2) -> v }.toMap
  val output      = opts.getOrElse("output", "/tmp/mm1.jsonl")
  val seed        = opts.get("seed").map(_.toLong).getOrElse(1L)
  val ticks       = opts.get("ticks").map(_.toLong).getOrElse(300L)
  val warmup      = opts.get("warmup").map(_.toLong).getOrElse(math.max(1L, ticks / 5L))
  val trials      = opts.get("trials").map(_.toInt).getOrElse(200)
  val parallelism = opts.get("parallelism").map(_.toInt).getOrElse(8)

  val immediate = MM1Config(scenarioId = "mm1-immediate", simulationTicks = ticks, warmupTicks = warmup,
                            trialCount = trials, parallelism = parallelism)
  val thinkTime = immediate.copy(scenarioId = "mm1-think-time", thinkTimeMean = Some(0.02))

  given system: ActorSystem = ActorSystem("mm1-demo")
  import system.dispatcher
  try
    val results = Vector(immediate, thinkTime).map { config =>
      val result = Await.result(MM1MonteCarloRunner.run(config, seed), 30.minutes)
      print(MM1Report.summary(result))
      result
    }
    Files.write(Paths.get(output), results.map(MM1Report.jsonl).mkString.getBytes("UTF-8"))
    println(s"[mm1-demo] wrote ${results.map(_.trials.size).sum} JSONL records to $output")
  finally system.terminate()
