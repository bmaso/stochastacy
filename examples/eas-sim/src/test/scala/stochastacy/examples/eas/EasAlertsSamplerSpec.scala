package stochastacy.examples.eas

import org.apache.commons.rng.simple.RandomSource
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.{DynamoDbReadTarget, GetItemRequest, PutItemRequest, QueryRequest, RequestedReadShape}
import stochastacy.aws.dynamodb.table.{ReadConsistency, SamplerContext, SummaryTableState, TableState, ProjectionSatisfaction}
import stochastacy.aws.dynamodb.table.LogicalPartitionAccess.SingleLogicalPartitionKey
import stochastacy.sim.SimTime

class EasAlertsSamplerSpec extends AnyWordSpec with Matchers:

  private val config  = EasAlertsConfig()
  private val sampler = EasAlertsSampler(config, RandomSource.KISS.create(42L))
  private def ctx     = SamplerContext[TableState](SummaryTableState(0L, 0L), 1L)

  private def queryRequest = QueryRequest(
    eventTime        = SimTime.of(1L),
    usecase          = "A1",
    target             = DynamoDbReadTarget.GlobalSecondaryIndex("alerts", "by-region-index"),
    readConsistency    = ReadConsistency.EventuallyConsistent,
    requestedReadShape = RequestedReadShape.AllProjectedOrFullItem,
    flowId           = Some("a1-poll")
  )

  private def getItemRequest = GetItemRequest(
    eventTime = SimTime.of(1L),
    usecase   = "A2",
    flowId    = Some("a2-fetch")
  )

  private def putItemRequest = PutItemRequest(
    eventTime = SimTime.of(1L),
    usecase   = "A3",
    itemBytes = 4500L,
    flowId    = Some("a3-write")
  )

  // ── A1 query ───────────────────────────────────────────────────────────────

  "EasAlertsSampler.query (A1)" should {

    "return evaluatedItemCount within configured scanned range" in {
      val results = (1 to 200).map(_ => sampler.query(queryRequest, ctx))
      results.foreach { s =>
        s.evaluatedItemCount should (be >= config.scannedItemsMin.toLong and
                                     be <= config.scannedItemsMax.toLong)
      }
    }

    "always return exactly 1 item (active alert)" in {
      val results = (1 to 50).map(_ => sampler.query(queryRequest, ctx))
      results.foreach(_.returnedItemCount shouldBe 1L)
    }

    "return returnedBytes <= evaluatedBytes" in {
      val results = (1 to 50).map(_ => sampler.query(queryRequest, ctx))
      results.foreach(s => s.returnedBytes should be <= s.evaluatedBytes)
    }

    "use evaluatedBytes consistent with scannedCount * perItemBytes" in {
      val results = (1 to 200).map(_ => sampler.query(queryRequest, ctx))
      results.foreach { s =>
        // evaluatedBytes = scannedCount * perItemBytes, where perItemBytes is uniform
        // in [projectedItemMinBytes, projectedItemMaxBytes].  evaluatedBytes / scannedCount
        // must land in that range.
        val perItem = s.evaluatedBytes / s.evaluatedItemCount
        perItem should (be >= config.projectedItemMinBytes and
                        be <= config.projectedItemMaxBytes)
      }
    }

    "use the configured region as the GSI partition key (hot partition)" in {
      val results = (1 to 20).map(_ => sampler.query(queryRequest, ctx))
      results.foreach { s =>
        s.logicalPartitionAccess shouldBe SingleLogicalPartitionKey(config.region)
      }
    }

    "set projectionSatisfaction to FullySatisfiedByIndex" in {
      val results = (1 to 20).map(_ => sampler.query(queryRequest, ctx))
      results.foreach(_.projectionSatisfaction shouldBe ProjectionSatisfaction.FullySatisfiedByIndex)
    }
  }

  // ── A2 getItem ─────────────────────────────────────────────────────────────

  "EasAlertsSampler.getItem (A2)" should {

    "return a hit (Some itemBytes) with positive byte count" in {
      val results = (1 to 50).map(_ => sampler.getItem(getItemRequest, ctx))
      results.foreach { s =>
        s.itemBytes shouldBe defined
        s.itemBytes.get should be > 0L
      }
    }

    "produce item sizes distributed around the log-normal median (~4500 bytes)" in {
      val sizes = (1 to 500).map(_ => sampler.getItem(getItemRequest, ctx).itemBytes.get)
      // Loose bounds: all positive, median in reasonable range, some above and below 4KB
      sizes.foreach(_ should be > 0L)
      val median = sizes.sorted.apply(250)
      median should (be > 2000L and be < 8000L)
      sizes.count(_ > 4096L) should (be > 50 and be < 450)  // not all above or all below 4KB
    }

    "use the configured alertId as the partition key (hot partition)" in {
      val results = (1 to 20).map(_ => sampler.getItem(getItemRequest, ctx))
      results.foreach { s =>
        s.logicalPartitionAccess shouldBe SingleLogicalPartitionKey(config.alertId)
      }
    }
  }

  // ── A3 putItem ─────────────────────────────────────────────────────────────

  "EasAlertsSampler.putItem (A3)" should {

    "return positive writtenItemBytes" in {
      val results = (1 to 50).map(_ => sampler.putItem(putItemRequest, ctx))
      results.foreach(_.writtenItemBytes should be > 0L)
    }

    "always set previousItemBytes to None (new alert)" in {
      val results = (1 to 50).map(_ => sampler.putItem(putItemRequest, ctx))
      results.foreach(_.previousItemBytes shouldBe None)
    }

    "use the configured alertId as the partition key" in {
      val results = (1 to 20).map(_ => sampler.putItem(putItemRequest, ctx))
      results.foreach { s =>
        s.logicalPartitionAccess shouldBe SingleLogicalPartitionKey(config.alertId)
      }
    }
  }
