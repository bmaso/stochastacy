package stochastacy.aws.dynamodb.client

/** Pure-function backoff bucketing helper.  Given an `SdkRetryStrategy` and an
 *  attempt number, computes the probability distribution over future-tick
 *  buckets for a retry of that attempt.
 *
 *  This is the analytic building block for the stochastic Multinomial draw
 *  that `SdkClientStage` (Slice C) will use to bucket-count many retries at
 *  once — without simulating each retry's delay individually. */
object BackoffDistribution:

  /** Computes the probability distribution over future-tick buckets for a single
   *  retry of the given attempt number under the given strategy.
   *
   *  Element `i` in the returned vector is the probability that the retry
   *  (after jitter is applied) lands `i` ticks after the failure tick.  Weights
   *  sum to 1.0 within floating-point tolerance.  The vector may contain
   *  trailing zeros — callers should not assume its length is minimal.
   *
   *  Neither `strategy.retryProportion` nor `strategy.retryable` is consulted
   *  here.  Those knobs govern whether a response triggers a retry at all;
   *  this function computes only WHERE in time a triggered retry lands.
   *
   *  @param strategy            Retry policy (baseBackoff, maxBackoff, jitter).
   *  @param attempt             Which retry attempt.  Must be >= 1: attempt 1
   *                             is the first retry after the initial request
   *                             failed; attempt 2 is the second, etc.
   *  @param tickDurationSeconds Simulation tick length in wall-clock seconds.
   *                             Must be > 0. */
  def bucketWeights(
    strategy: SdkRetryStrategy,
    attempt: Int,
    tickDurationSeconds: Double
  ): Vector[Double] =
    require(attempt >= 1,
      s"attempt must be >= 1 (attempt 0 is the initial request, not a retry), got $attempt")
    require(tickDurationSeconds > 0.0,
      s"tickDurationSeconds must be positive, got $tickDurationSeconds")

    val nominalMs = nominalDelayMs(strategy, attempt)
    val tickMs    = tickDurationSeconds * 1000.0

    strategy.jitter match
      case JitterStrategy.None  => pointMassWeights(nominalMs, tickMs)
      case JitterStrategy.Full  => uniformWeights(0.0, nominalMs, tickMs)
      case JitterStrategy.Equal => uniformWeights(nominalMs / 2.0, nominalMs, tickMs)

  /** Nominal exponential backoff for attempt N, capped at strategy.maxBackoff.
   *  Computed in Double to avoid Long overflow at large attempt numbers — the
   *  cap makes precision loss beyond maxBackoff irrelevant. */
  private def nominalDelayMs(strategy: SdkRetryStrategy, attempt: Int): Double =
    val uncapped = strategy.baseBackoff.toMillis.toDouble * math.pow(2.0, (attempt - 1).toDouble)
    math.min(uncapped, strategy.maxBackoff.toMillis.toDouble)

  /** All weight on bucket floor(delayMs / tickMs). */
  private def pointMassWeights(delayMs: Double, tickMs: Double): Vector[Double] =
    val bucket = math.floor(delayMs / tickMs).toInt
    Vector.tabulate(bucket + 1)(i => if i == bucket then 1.0 else 0.0)

  /** Uniform(lo, hi) distributed across bucket intervals of width tickMs.
   *  Bucket i covers [i * tickMs, (i+1) * tickMs); its weight is the overlap
   *  of that interval with [lo, hi] divided by (hi - lo).
   *
   *  Degenerate case (lo == hi) collapses to a point mass at hi. */
  private def uniformWeights(lo: Double, hi: Double, tickMs: Double): Vector[Double] =
    val range = hi - lo
    if range == 0.0 then pointMassWeights(hi, tickMs)
    else
      val maxBucket = math.floor(hi / tickMs).toInt
      Vector.tabulate(maxBucket + 1) { i =>
        val bucketLo   = i * tickMs
        val bucketHi   = (i + 1) * tickMs
        val overlapLo  = math.max(lo, bucketLo)
        val overlapHi  = math.min(hi, bucketHi)
        val overlap    = math.max(0.0, overlapHi - overlapLo)
        overlap / range
      }
