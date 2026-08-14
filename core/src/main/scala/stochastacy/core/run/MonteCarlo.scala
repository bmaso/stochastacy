package stochastacy.core.run

import scala.concurrent.{ExecutionContext, Future}

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Sink, Source}

/** The Monte Carlo executor: run `trialCount` independent trials from one master seed, with bounded
 *  parallelism, and collect their results. Generic over the per-trial result `R` and the trial
 *  runner (`seed => Future[R]`) — it owns only the mechanics (seed derivation, the concurrency cap,
 *  order-stable collection), never what a trial computes or how results are aggregated. Aggregation
 *  is the caller's concern (see the store example's `StoreMonteCarloResult`).
 *
 *  **Determinism:** seeds come from [[SeedSequence]] and `mapAsync` emits in input order regardless
 *  of completion order, so — provided `runTrial` is deterministic given its seed — the returned
 *  vector is identical for any `parallelism`. */
object MonteCarlo:

  def run[R](
    trialCount:  Int,
    masterSeed:  Long,
    parallelism: Int = 4
  )(runTrial: Long => Future[R])(using system: ActorSystem): Future[Vector[R]] =
    given ExecutionContext = system.dispatcher
    Source(SeedSequence.derive(masterSeed, trialCount))
      .mapAsync(math.max(1, parallelism))(runTrial)
      .runWith(Sink.seq)
      .map(_.toVector)
