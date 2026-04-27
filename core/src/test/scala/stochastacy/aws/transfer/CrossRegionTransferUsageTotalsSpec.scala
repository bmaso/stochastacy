package stochastacy.aws.transfer

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.sim.SimTime

class CrossRegionTransferUsageTotalsSpec extends AnyWordSpec with should.Matchers:

  "CrossRegionTransferUsageTotals" should {

    "start with zero totals" in {
      val totals = CrossRegionTransferUsageTotals()
      totals.overall.totalBytes shouldBe 0L
      totals.byDirectionalPair shouldBe empty
      totals.byService shouldBe empty
    }

    "accumulate a single event into all three breakdowns" in {
      val evt = CrossRegionTransferEvent(
        eventTime = SimTime.of(1L),
        usecase = "test",
        sourceRegion = "us-east-1",
        destinationRegion = "eu-west-1",
        sourceService = "DynamoDB",
        bytes = 1024L
      )
      val acc = CrossRegionTransferUsageTotals.accumulate(CrossRegionTransferUsageTotals(), evt)

      acc.overall.totalBytes shouldBe 1024L
      acc.byDirectionalPair(("us-east-1", "eu-west-1")).totalBytes shouldBe 1024L
      acc.byService("DynamoDB").totalBytes shouldBe 1024L
    }

    "sum multiple events on the same directional pair" in {
      val mkEvent = (bytes: Long) =>
        CrossRegionTransferEvent(SimTime.of(1L), "u", "us-east-1", "eu-west-1", "DynamoDB", bytes)
      val acc =
        Seq(mkEvent(100L), mkEvent(200L), mkEvent(50L))
          .foldLeft(CrossRegionTransferUsageTotals())(CrossRegionTransferUsageTotals.accumulate)

      acc.overall.totalBytes shouldBe 350L
      acc.byDirectionalPair(("us-east-1", "eu-west-1")).totalBytes shouldBe 350L
      acc.byDirectionalPair should have size 1
    }

    "track multiple directional pairs separately" in {
      val events = Seq(
        CrossRegionTransferEvent(SimTime.of(1L), "u", "us-east-1", "eu-west-1", "DynamoDB", 100L),
        CrossRegionTransferEvent(SimTime.of(2L), "u", "us-east-1", "ap-southeast-2", "DynamoDB", 200L),
        CrossRegionTransferEvent(SimTime.of(3L), "u", "eu-west-1", "us-east-1", "DynamoDB", 50L)
      )
      val acc = events.foldLeft(CrossRegionTransferUsageTotals())(CrossRegionTransferUsageTotals.accumulate)

      acc.overall.totalBytes shouldBe 350L
      acc.byDirectionalPair(("us-east-1", "eu-west-1")).totalBytes shouldBe 100L
      acc.byDirectionalPair(("us-east-1", "ap-southeast-2")).totalBytes shouldBe 200L
      acc.byDirectionalPair(("eu-west-1", "us-east-1")).totalBytes shouldBe 50L
      acc.byDirectionalPair should have size 3
    }

    "track multiple services separately" in {
      val events = Seq(
        CrossRegionTransferEvent(SimTime.of(1L), "u", "us-east-1", "eu-west-1", "DynamoDB", 100L),
        CrossRegionTransferEvent(SimTime.of(2L), "u", "us-east-1", "eu-west-1", "S3", 200L),
        CrossRegionTransferEvent(SimTime.of(3L), "u", "us-east-1", "eu-west-1", "DynamoDB", 50L)
      )
      val acc = events.foldLeft(CrossRegionTransferUsageTotals())(CrossRegionTransferUsageTotals.accumulate)

      acc.overall.totalBytes shouldBe 350L
      acc.byService("DynamoDB").totalBytes shouldBe 150L
      acc.byService("S3").totalBytes shouldBe 200L
      acc.byService should have size 2
      // Same directional pair across services lumps together in the pair breakdown
      acc.byDirectionalPair(("us-east-1", "eu-west-1")).totalBytes shouldBe 350L
    }

    "treat directional pairs as ordered (A→B is distinct from B→A)" in {
      val acc = Seq(
        CrossRegionTransferEvent(SimTime.of(1L), "u", "us-east-1", "eu-west-1", "DynamoDB", 100L),
        CrossRegionTransferEvent(SimTime.of(2L), "u", "eu-west-1", "us-east-1", "DynamoDB", 100L)
      ).foldLeft(CrossRegionTransferUsageTotals())(CrossRegionTransferUsageTotals.accumulate)

      acc.byDirectionalPair(("us-east-1", "eu-west-1")).totalBytes shouldBe 100L
      acc.byDirectionalPair(("eu-west-1", "us-east-1")).totalBytes shouldBe 100L
    }
  }

  "CrossRegionTransferEvent" should {
    "reject empty source region" in {
      an[IllegalArgumentException] should be thrownBy
        CrossRegionTransferEvent(SimTime.of(1L), "u", "", "eu-west-1", "DynamoDB", 1L)
    }

    "reject empty destination region" in {
      an[IllegalArgumentException] should be thrownBy
        CrossRegionTransferEvent(SimTime.of(1L), "u", "us-east-1", "", "DynamoDB", 1L)
    }

    "reject empty source service" in {
      an[IllegalArgumentException] should be thrownBy
        CrossRegionTransferEvent(SimTime.of(1L), "u", "us-east-1", "eu-west-1", "", 1L)
    }

    "reject negative bytes" in {
      an[IllegalArgumentException] should be thrownBy
        CrossRegionTransferEvent(SimTime.of(1L), "u", "us-east-1", "eu-west-1", "DynamoDB", -1L)
    }

    "accept zero bytes" in {
      noException should be thrownBy
        CrossRegionTransferEvent(SimTime.of(1L), "u", "us-east-1", "eu-west-1", "DynamoDB", 0L)
    }
  }
