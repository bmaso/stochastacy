package stochastacy.aws.dynamodb

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class TableSummaryStateSpec extends AnyWordSpec with should.Matchers:

  "TableSummaryState.initial" should {
    "pre-load count × average bytes" in {
      val s = TableSummaryState.initial(itemCount = 10L, averageItemBytes = 768L)
      s.itemCount      shouldBe 10L
      s.totalItemBytes shouldBe 7680L
      s.averageItemBytes shouldBe Some(768L)
    }
  }

  "TableSummaryState.averageItemBytes" should {
    "be None for an empty table" in {
      TableSummaryState.empty.averageItemBytes shouldBe None
    }
  }

  "TableSummaryState.applyWrite" should {
    "insert a new item when there is no previous (count and bytes grow)" in {
      val s = TableSummaryState.initial(10L, 768L).applyWrite(writtenItemBytes = 800L, previousItemBytes = None)
      s.itemCount      shouldBe 11L
      s.totalItemBytes shouldBe 8480L
    }

    "replace an existing item in place (count unchanged, bytes adjusted by the difference)" in {
      val s = TableSummaryState.initial(10L, 768L).applyWrite(writtenItemBytes = 900L, previousItemBytes = Some(768L))
      s.itemCount      shouldBe 10L
      s.totalItemBytes shouldBe (7680L - 768L + 900L)
    }
  }

  "TableSummaryState.applyDelete" should {
    "remove an existing item (count and bytes shrink)" in {
      val s = TableSummaryState.initial(10L, 768L).applyDelete(Some(768L))
      s.itemCount      shouldBe 9L
      s.totalItemBytes shouldBe 6912L
    }

    "be a no-op for an absent item" in {
      val start = TableSummaryState.initial(10L, 768L)
      start.applyDelete(None) shouldBe start
    }
  }
