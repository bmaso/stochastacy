package stochastacy.core.run

import scala.concurrent.{ExecutionContext, Future}

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Sink, Source}

/** The Monte Carlo executor: run `trialCount` independent trials from one master seed, with bounded
 *  parallelism. Generic over the per-trial result `R` and the trial runner (`seed => Future[R]`) — it
 *  owns only the mechanics (seed derivation, the concurrency cap, order-stable emission), never what a
 *  trial computes or how results are aggregated. Aggregation is the caller's concern (see the store
 *  example's `StoreMonteCarloResult`).
 *
 *  **Determinism:** seeds come from [[SeedSequence]] and `mapAsync` emits in input order regardless
 *  of completion order, so — provided `runTrial` is deterministic given its seed — the emitted
 *  sequence is identical for any `parallelism`. */
object MonteCarlo:

  /** The trials as a **stream**, emitted in seed order (so a downstream fold can accumulate and release
   *  each trial without ever holding them all in memory). This is the bounded-memory building block; the
   *  collecting [[run]] is `stream(...).runWith(Sink.seq)`. */
  def stream[R](
    trialCount:  Int,
    masterSeed:  Long,
    parallelism: Int = 4
  )(runTrial: Long => Future[R]): Source[R, NotUsed] =
    Source(SeedSequence.derive(masterSeed, trialCount))
      .mapAsync(math.max(1, parallelism))(runTrial)

  /** Run `trialCount` trials and collect their results into an order-stable vector. */
  def run[R](
    trialCount:  Int,
    masterSeed:  Long,
    parallelism: Int = 4
  )(runTrial: Long => Future[R])(using system: ActorSystem): Future[Vector[R]] =
    given ExecutionContext = system.dispatcher
    stream(trialCount, masterSeed, parallelism)(runTrial)
      .runWith(Sink.seq)
      .map(_.toVector)
