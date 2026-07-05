package stochastacy.aws.dynamodb.client

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration.*

class BackoffDistributionSpec extends AnyWordSpec with should.Matchers:

  private val Tol: Double = 1e-9

  private def approxEqual(a: Double, b: Double, tol: Double = Tol): Boolean =
    math.abs(a - b) < tol

  private def sum(v: Vector[Double]): Double = v.sum

  // ── Preconditions ──────────────────────────────────────────────────────────

  "BackoffDistribution.bucketWeights preconditions" should {

    val s = SdkRetryStrategy.awsJavaSdkV2Standard

    "reject attempt = 0" in {
      val ex = intercept[IllegalArgumentException] {
        BackoffDistribution.bucketWeights(s, attempt = 0, tickDurationSeconds = 1.0)
      }
      ex.getMessage should include("attempt 0 is the initial request")
    }

    "reject attempt < 0" in {
      an [IllegalArgumentException] should be thrownBy
        BackoffDistribution.bucketWeights(s, attempt = -1, tickDurationSeconds = 1.0)
    }

    "reject tickDurationSeconds = 0" in {
      an [IllegalArgumentException] should be thrownBy
        BackoffDistribution.bucketWeights(s, attempt = 1, tickDurationSeconds = 0.0)
    }

    "reject negative tickDurationSeconds" in {
      an [IllegalArgumentException] should be thrownBy
        BackoffDistribution.bucketWeights(s, attempt = 1, tickDurationSeconds = -1.0)
    }
  }

  // ── Weight-sanity (parametrised over presets × attempts) ───────────────────

  "BackoffDistribution.bucketWeights preset sanity" should {

    val presets: Seq[(String, SdkRetryStrategy)] = Seq(
      "awsJavaSdkV2Standard" -> SdkRetryStrategy.awsJavaSdkV2Standard,
      "boto3Standard"        -> SdkRetryStrategy.boto3Standard
    )

    for
      (name, strat) <- presets
      attempt       <- 1 until strat.maxAttempts
    do
      s"$name attempt=$attempt produces a valid probability vector" in {
        val w = BackoffDistribution.bucketWeights(strat, attempt, tickDurationSeconds = 1.0)
        w should not be empty
        w.foreach(x => x should (be >= 0.0 and be <= 1.0))
        approxEqual(sum(w), 1.0) shouldBe true
      }
  }

  // ── JitterStrategy.None ────────────────────────────────────────────────────

  "BackoffDistribution.bucketWeights with JitterStrategy.None" should {

    val strat = SdkRetryStrategy(
      maxAttempts = 8,
      baseBackoff = 100.millis,
      maxBackoff  = 20.seconds,
      jitter      = JitterStrategy.None
    )

    "produce a point mass at bucket 0 for a small nominal delay" in {
      // attempt=1, nominal=100ms < 1s → bucket 0.
      BackoffDistribution.bucketWeights(strat, attempt = 1, tickDurationSeconds = 1.0) shouldBe Vector(1.0)
    }

    "produce a point mass at bucket 0 for attempts still under one tick" in {
      // attempt=4, nominal=100*8=800ms < 1s → bucket 0.
      BackoffDistribution.bucketWeights(strat, attempt = 4, tickDurationSeconds = 1.0) shouldBe Vector(1.0)
    }

    "produce a point mass at the correct bucket for a large nominal delay" in {
      // attempt=7, nominal=100*64=6400ms → floor(6400/1000)=6, bucket 6.
      val w = BackoffDistribution.bucketWeights(strat, attempt = 7, tickDurationSeconds = 1.0)
      w.length shouldBe 7
      w.zipWithIndex.foreach { case (weight, i) =>
        if i == 6 then weight shouldBe 1.0 else weight shouldBe 0.0
      }
    }

    "cap at maxBackoff for very high attempts" in {
      val cappedStrat = SdkRetryStrategy(
        maxAttempts = 20,
        baseBackoff = 1.second,
        maxBackoff  = 1.second,
        jitter      = JitterStrategy.None
      )
      // Any attempt >= 1 is capped at nominal=1000ms → bucket 1.
      val w1  = BackoffDistribution.bucketWeights(cappedStrat, attempt = 1,  tickDurationSeconds = 1.0)
      val w10 = BackoffDistribution.bucketWeights(cappedStrat, attempt = 10, tickDurationSeconds = 1.0)
      w1  shouldBe Vector(0.0, 1.0)
      w10 shouldBe Vector(0.0, 1.0)
    }
  }

  // ── JitterStrategy.Full ────────────────────────────────────────────────────

  "BackoffDistribution.bucketWeights with JitterStrategy.Full" should {

    val strat = SdkRetryStrategy(
      maxAttempts = 8,
      baseBackoff = 100.millis,
      maxBackoff  = 20.seconds,
      jitter      = JitterStrategy.Full
    )

    "keep all weight in bucket 0 when nominal < tick" in {
      // Uniform(0, 100ms) → all in bucket 0.
      BackoffDistribution.bucketWeights(strat, attempt = 1, tickDurationSeconds = 1.0) shouldBe Vector(1.0)
    }

    "split proportionally across two buckets when nominal spans one boundary" in {
      // attempt=5, nominal=100*16=1600ms → Uniform(0, 1600).
      // Bucket 0: [0, 1000)/1600 = 0.625.  Bucket 1: [1000, 1600)/1600 = 0.375.
      val w = BackoffDistribution.bucketWeights(strat, attempt = 5, tickDurationSeconds = 1.0)
      w.length shouldBe 2
      approxEqual(w(0), 1000.0 / 1600.0) shouldBe true
      approxEqual(w(1),  600.0 / 1600.0) shouldBe true
    }

    "handle the exact-boundary case (nominal == tick) by leaving a trailing zero" in {
      // Uniform(0, 1000) with 1000ms/tick.  Every draw is in [0, 1000), so bucket 0.
      // maxBucket = floor(1000/1000) = 1, so vector length 2 with a trailing zero.
      val boundaryStrat = SdkRetryStrategy(
        maxAttempts = 2,
        baseBackoff = 1000.millis,
        maxBackoff  = 20.seconds,
        jitter      = JitterStrategy.Full
      )
      val w = BackoffDistribution.bucketWeights(boundaryStrat, attempt = 1, tickDurationSeconds = 1.0)
      w.length shouldBe 2
      approxEqual(w(0), 1.0) shouldBe true
      approxEqual(w(1), 0.0) shouldBe true
    }
  }

  // ── JitterStrategy.Equal ───────────────────────────────────────────────────

  "BackoffDistribution.bucketWeights with JitterStrategy.Equal" should {

    val strat = SdkRetryStrategy(
      maxAttempts = 8,
      baseBackoff = 100.millis,
      maxBackoff  = 20.seconds,
      jitter      = JitterStrategy.Equal
    )

    "keep all weight in bucket 0 when nominal < tick" in {
      // Uniform(50ms, 100ms) — all in bucket 0.
      BackoffDistribution.bucketWeights(strat, attempt = 1, tickDurationSeconds = 1.0) shouldBe Vector(1.0)
    }

    "split proportionally across two buckets when the interval spans a boundary" in {
      // attempt=5, nominal=1600ms → Uniform(800, 1600).  Range = 800ms.
      // Bucket 0: [800, 1000)/800 = 0.25.  Bucket 1: [1000, 1600)/800 = 0.75.
      val w = BackoffDistribution.bucketWeights(strat, attempt = 5, tickDurationSeconds = 1.0)
      w.length shouldBe 2
      approxEqual(w(0), 200.0 / 800.0) shouldBe true
      approxEqual(w(1), 600.0 / 800.0) shouldBe true
    }

    "leave leading zeros for buckets before the lower bound" in {
      // attempt=7, nominal=100*64=6400ms → Uniform(3200, 6400).  Range = 3200ms.
      // Buckets 0..2 have overlap 0; buckets 3..6 split the mass.
      val w = BackoffDistribution.bucketWeights(strat, attempt = 7, tickDurationSeconds = 1.0)
      w.length shouldBe 7
      approxEqual(w(0), 0.0) shouldBe true
      approxEqual(w(1), 0.0) shouldBe true
      approxEqual(w(2), 0.0) shouldBe true
      approxEqual(w(3),  800.0 / 3200.0) shouldBe true  // [3200, 4000)
      approxEqual(w(4), 1000.0 / 3200.0) shouldBe true  // [4000, 5000)
      approxEqual(w(5), 1000.0 / 3200.0) shouldBe true  // [5000, 6000)
      approxEqual(w(6),  400.0 / 3200.0) shouldBe true  // [6000, 6400)
      approxEqual(sum(w), 1.0) shouldBe true
    }
  }

  // ── Irrelevance of retryProportion / retryable ─────────────────────────────

  "BackoffDistribution.bucketWeights" should {

    "produce identical weights regardless of retryProportion" in {
      val s1 = SdkRetryStrategy(3, 100.millis, 20.seconds, JitterStrategy.Equal, retryProportion = 1.0)
      val s2 = SdkRetryStrategy(3, 100.millis, 20.seconds, JitterStrategy.Equal, retryProportion = 0.3)
      BackoffDistribution.bucketWeights(s1, 1, 1.0) shouldBe
        BackoffDistribution.bucketWeights(s2, 1, 1.0)
      BackoffDistribution.bucketWeights(s1, 2, 1.0) shouldBe
        BackoffDistribution.bucketWeights(s2, 2, 1.0)
    }

    "produce identical weights regardless of retryable classifier" in {
      val s1 = SdkRetryStrategy(3, 100.millis, 20.seconds, JitterStrategy.Full)
      val s2 = SdkRetryStrategy(3, 100.millis, 20.seconds, JitterStrategy.Full,
                                retryable = _ => true)
      BackoffDistribution.bucketWeights(s1, 1, 1.0) shouldBe
        BackoffDistribution.bucketWeights(s2, 1, 1.0)
    }
  }

  // ── Extreme cases ──────────────────────────────────────────────────────────

  "BackoffDistribution.bucketWeights extremes" should {

    "converge to the maxBackoff distribution once the exponential saturates" in {
      // Full jitter, base=100ms, max=1s → uncapped exceeds cap at attempt where 100*2^(n-1) >= 1000
      // → n-1 >= log2(10) ≈ 3.32 → n >= 5.  At attempt 5, 100*16=1600 > 1000 → nominal capped to 1000.
      // For attempts 5, 10, 100 the distribution is the same (Uniform(0, 1000)).
      val strat = SdkRetryStrategy(
        maxAttempts = 200,
        baseBackoff = 100.millis,
        maxBackoff  = 1.second,
        jitter      = JitterStrategy.Full
      )
      val w5   = BackoffDistribution.bucketWeights(strat, attempt = 5,   tickDurationSeconds = 1.0)
      val w10  = BackoffDistribution.bucketWeights(strat, attempt = 10,  tickDurationSeconds = 1.0)
      val w100 = BackoffDistribution.bucketWeights(strat, attempt = 100, tickDurationSeconds = 1.0)
      w5  shouldBe w10
      w10 shouldBe w100
    }

    "handle sub-second tickDuration by spreading across many buckets" in {
      // tick=10ms, base=100ms, max=1s.  Attempt 3, nominal=400ms, Equal jitter → Uniform(200, 400).
      // Range = 200ms; each bucket 10ms wide.  So 20 non-zero buckets from index 20 (200ms) to 39 (390-400ms).
      val strat = SdkRetryStrategy(
        maxAttempts = 5,
        baseBackoff = 100.millis,
        maxBackoff  = 1.second,
        jitter      = JitterStrategy.Equal
      )
      val w = BackoffDistribution.bucketWeights(strat, attempt = 3, tickDurationSeconds = 0.01)
      // maxBucket = floor(400/10) = 40 → vector length 41.
      w.length shouldBe 41
      // Buckets 0..19 should have weight 0.
      (0 until 20).foreach(i => approxEqual(w(i), 0.0) shouldBe true)
      // Buckets 20..39 each get 10ms of the 200ms range → weight 0.05.
      (20 until 40).foreach(i => approxEqual(w(i), 10.0 / 200.0) shouldBe true)
      // Bucket 40 covers [400, 410) which is outside [200, 400] → weight 0.
      approxEqual(w(40), 0.0) shouldBe true
      approxEqual(sum(w), 1.0) shouldBe true
    }
  }
