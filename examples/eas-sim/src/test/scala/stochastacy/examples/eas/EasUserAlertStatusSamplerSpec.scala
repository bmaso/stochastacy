package stochastacy.examples.eas

import org.apache.commons.rng.simple.RandomSource
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.{PutItemRequest, UpdateItemRequest}
import stochastacy.aws.dynamodb.table.{SamplerContext, SummaryTableState, TableState}
import stochastacy.aws.dynamodb.table.LogicalPartitionAccess.SingleLogicalPartitionKey
import stochastacy.sim.SimTime

class EasUserAlertStatusSamplerSpec extends AnyWordSpec with Matchers:

  private val config  = EasUserAlertStatusConfig()
  private val sampler = EasUserAlertStatusSampler(config, RandomSource.KISS.create(42L))
  private def ctx     = SamplerContext[TableState](SummaryTableState(0L, 0L), 1L)

  private def putItemRequest = PutItemRequest(
    eventTime = SimTime.of(1L),
    usecase   = "S1",
    itemBytes = 300L,
    flowId    = Some("s1-delivered")
  )

  private def updateItemRequest(usecase: String, flowId: String) = UpdateItemRequest(
    eventTime = SimTime.of(1L),
    usecase   = usecase,
    itemBytes = 300L,
    flowId    = Some(flowId)
  )

  // ── S1 putItem ─────────────────────────────────────────────────────────────

  "EasUserAlertStatusSampler.putItem (S1 DELIVERED)" should {

    "return positive writtenItemBytes" in {
      val results = (1 to 50).map(_ => sampler.putItem(putItemRequest, ctx))
      results.foreach(_.writtenItemBytes should be > 0L)
    }

    "return writtenItemBytes within configured [itemMinBytes, itemMaxBytes]" in {
      val results = (1 to 200).map(_ => sampler.putItem(putItemRequest, ctx))
      results.foreach { s =>
        s.writtenItemBytes should (be >= config.itemMinBytes and be <= config.itemMaxBytes)
      }
    }

    "always set previousItemBytes to None (new item — first time user sees this alert)" in {
      val results = (1 to 50).map(_ => sampler.putItem(putItemRequest, ctx))
      results.foreach(_.previousItemBytes shouldBe None)
    }

    "use a distributed partition key (not always the same userId)" in {
      val keys = (1 to 100).map { _ =>
        sampler.putItem(putItemRequest, ctx).logicalPartitionAccess
      }.collect { case SingleLogicalPartitionKey(k) => k }

      keys should have length 100
      // Distributed access: with 500K user population, 100 draws should be unique
      keys.distinct.length should be > 50
    }

    "use a SingleLogicalPartitionKey with user-prefixed token" in {
      val results = (1 to 20).map(_ => sampler.putItem(putItemRequest, ctx))
      results.foreach { s =>
        s.logicalPartitionAccess shouldBe a[SingleLogicalPartitionKey]
        val key = s.logicalPartitionAccess.asInstanceOf[SingleLogicalPartitionKey].keyToken
        key should startWith("user-")
      }
    }
  }

  // ── S2 updateItem (OPENED) ─────────────────────────────────────────────────

  "EasUserAlertStatusSampler.updateItem (S2 OPENED)" should {

    "return positive writtenItemBytes" in {
      val req     = updateItemRequest("S2", "s2-opened")
      val results = (1 to 50).map(_ => sampler.updateItem(req, ctx))
      results.foreach(_.writtenItemBytes should be > 0L)
    }

    "return writtenItemBytes within configured [itemMinBytes, itemMaxBytes]" in {
      val req     = updateItemRequest("S2", "s2-opened")
      val results = (1 to 200).map(_ => sampler.updateItem(req, ctx))
      results.foreach { s =>
        s.writtenItemBytes should (be >= config.itemMinBytes and be <= config.itemMaxBytes)
      }
    }

    "set previousItemBytes to Some(n) where n > 0 (item exists from S1)" in {
      val req     = updateItemRequest("S2", "s2-opened")
      val results = (1 to 50).map(_ => sampler.updateItem(req, ctx))
      results.foreach { s =>
        s.previousItemBytes shouldBe defined
        s.previousItemBytes.get should be > 0L
      }
    }

    "return previousItemBytes within configured range" in {
      val req     = updateItemRequest("S2", "s2-opened")
      val results = (1 to 200).map(_ => sampler.updateItem(req, ctx))
      results.foreach { s =>
        s.previousItemBytes.get should (be >= config.itemMinBytes and be <= config.itemMaxBytes)
      }
    }

    "use a distributed partition key" in {
      val req  = updateItemRequest("S2", "s2-opened")
      val keys = (1 to 100).map { _ =>
        sampler.updateItem(req, ctx).logicalPartitionAccess
      }.collect { case SingleLogicalPartitionKey(k) => k }

      keys.distinct.length should be > 50
    }
  }

  // ── S3 updateItem (ACKNOWLEDGED) ──────────────────────────────────────────

  "EasUserAlertStatusSampler.updateItem (S3 ACKNOWLEDGED)" should {

    "return positive writtenItemBytes" in {
      val req     = updateItemRequest("S3", "s3-acknowledged")
      val results = (1 to 50).map(_ => sampler.updateItem(req, ctx))
      results.foreach(_.writtenItemBytes should be > 0L)
    }

    "set previousItemBytes to Some(n) where n > 0 (item exists from S2)" in {
      val req     = updateItemRequest("S3", "s3-acknowledged")
      val results = (1 to 50).map(_ => sampler.updateItem(req, ctx))
      results.foreach { s =>
        s.previousItemBytes shouldBe defined
        s.previousItemBytes.get should be > 0L
      }
    }

    "use a distributed partition key" in {
      val req  = updateItemRequest("S3", "s3-acknowledged")
      val keys = (1 to 100).map { _ =>
        sampler.updateItem(req, ctx).logicalPartitionAccess
      }.collect { case SingleLogicalPartitionKey(k) => k }

      keys.distinct.length should be > 50
    }

    "produce the same stochastic structure as S2 (identical implementation path)" in {
      // Both S2 and S3 go through the same updateItem() body.
      // This test checks they're statistically indistinguishable on writtenItemBytes.
      val s2Sampler = EasUserAlertStatusSampler(config, RandomSource.KISS.create(99L))
      val s3Sampler = EasUserAlertStatusSampler(config, RandomSource.KISS.create(99L))
      val s2Req     = updateItemRequest("S2", "s2-opened")
      val s3Req     = updateItemRequest("S3", "s3-acknowledged")

      val s2Sizes = (1 to 200).map(_ => s2Sampler.updateItem(s2Req, ctx).writtenItemBytes)
      val s3Sizes = (1 to 200).map(_ => s3Sampler.updateItem(s3Req, ctx).writtenItemBytes)

      // Same seed, same config — outputs should be identical (implementation reuse check)
      s2Sizes shouldEqual s3Sizes
    }
  }
