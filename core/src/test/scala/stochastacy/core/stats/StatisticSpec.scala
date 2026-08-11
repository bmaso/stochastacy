package stochastacy.core.stats

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class StatisticSpec extends AnyWordSpec with should.Matchers:

  private def fold(values: Seq[Double]): Statistic =
    values.foldLeft(Statistic.empty)(_ observe _)

  "Statistic moments" should {
    "compute count, mean and stddev" in {
      val s = fold(Seq(1.0, 2.0, 3.0))
      s.count shouldBe 3L
      s.mean shouldBe 2.0
      s.stddev shouldBe (math.sqrt(2.0 / 3.0) +- 1e-9) // population stddev of {1,2,3}
    }
    "report zero mean/stddev for the empty statistic" in {
      Statistic.empty.mean shouldBe 0.0
      Statistic.empty.stddev shouldBe 0.0
    }
  }

  "Statistic quantiles" should {
    "estimate a constant distribution near the constant" in {
      val s = fold(Seq.fill(100)(250.0))
      s.p50 shouldBe (250.0 +- 250.0 * 0.10) // within one log-bucket width (~Base-1)
      s.p99 shouldBe (250.0 +- 250.0 * 0.10)
    }
    "estimate quantiles of a uniform 1..1000 spread within tolerance" in {
      val s = fold((1 to 1000).map(_.toDouble))
      s.p50 shouldBe (500.0 +- 500.0 * 0.15)
      s.p99 shouldBe (990.0 +- 990.0 * 0.15)
      s.p50 should be < s.p99
    }
  }

  "Statistic.combine" should {
    "be associative" in {
      val a = fold(Seq(1.0, 2.0, 7.0))
      val b = fold(Seq(3.0, 4.0, 20.0))
      val c = fold(Seq(5.0, 6.0, 100.0))
      a.combine(b.combine(c)) shouldBe a.combine(b).combine(c)
    }
    "equal folding all values into one statistic" in {
      val left  = fold(Seq(1.0, 2.0)).combine(fold(Seq(3.0, 4.0)))
      val whole = fold(Seq(1.0, 2.0, 3.0, 4.0))
      left shouldBe whole
    }
  }
