package stochastacy.core.stats

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class StatisticsSpec extends AnyWordSpec with should.Matchers:

  "Statistics" should {

    "accumulate per-key statistics independently" in {
      val stats = Statistics
        .empty[String]
        .observe("a", 1.0)
        .observe("a", 3.0)
        .observe("b", 10.0)

      stats.get("a").map(_.count) shouldBe Some(2L)
      stats.get("a").map(_.mean) shouldBe Some(2.0)
      stats.get("b").map(_.mean) shouldBe Some(10.0)
      stats.get("missing") shouldBe None
    }

    "combine key-wise" in {
      val x = Statistics.empty[String].observe("a", 1.0).observe("b", 2.0)
      val y = Statistics.empty[String].observe("a", 3.0).observe("c", 4.0)
      val z = x.combine(y)

      z.get("a").map(_.count) shouldBe Some(2L) // merged
      z.get("b").map(_.count) shouldBe Some(1L)
      z.get("c").map(_.count) shouldBe Some(1L)
      z.get("a").map(_.mean) shouldBe Some(2.0)
    }
  }
