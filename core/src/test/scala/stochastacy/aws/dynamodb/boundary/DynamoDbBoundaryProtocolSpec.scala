package stochastacy.aws.dynamodb.boundary

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.*
import stochastacy.sim.{SimTime, ticks}

/**
 * The DynamoDB `BoundaryProtocol` restamp dispatchers preserve the concrete
 * type and every non-timing field while updating `eventTime` / `intraTick`.
 * Representative coverage across request and response types with distinct
 * field sets; the dispatch is total by construction (all 9 request / 13
 * response case classes are enumerated).
 */
class DynamoDbBoundaryProtocolSpec extends AnyWordSpec with should.Matchers:

  private val P = DynamoDbBoundaryProtocol
  private val newTime = SimTime.of(7L)
  private val newIt   = 0.42

  "withRequestTiming" should {

    "restamp a GetItemRequest and preserve other fields" in {
      val r   = GetItemRequest(SimTime.of(2L), "uc", intraTick = 0.1, flowId = Some("f"), clientAttempt = 3)
      val out = P.withRequestTiming(r, newTime, newIt)
      out shouldBe a[GetItemRequest]
      out.eventTime.ticks shouldBe 7L
      out.intraTick shouldBe (0.42 +- 1e-9)
      out.flowId shouldBe Some("f")
      out.clientAttempt shouldBe 3
      out.asInstanceOf[GetItemRequest].usecase shouldBe "uc"
    }

    "restamp a PutItemRequest and preserve itemBytes" in {
      val r   = PutItemRequest(SimTime.of(2L), "uc", itemBytes = 512L, flowId = Some("f"))
      val out = P.withRequestTiming(r, newTime, newIt)
      out shouldBe a[PutItemRequest]
      out.eventTime.ticks shouldBe 7L
      out.asInstanceOf[PutItemRequest].itemBytes shouldBe 512L
      out.flowId shouldBe Some("f")
    }

    "restamp a DeleteItemRequest" in {
      val r   = DeleteItemRequest(SimTime.of(2L), "uc", clientAttempt = 1)
      val out = P.withRequestTiming(r, newTime, newIt)
      out shouldBe a[DeleteItemRequest]
      out.eventTime.ticks shouldBe 7L
      out.intraTick shouldBe (0.42 +- 1e-9)
      out.clientAttempt shouldBe 1
    }
  }

  "withResponseTiming" should {

    "restamp a GetItemResponse and preserve payload + originalRequest" in {
      val orig = GetItemRequest(SimTime.of(2L), "uc")
      val r    = GetItemResponse(SimTime.of(2L), "uc", itemFound = true, itemBytes = Some(100L),
                                 flowId = Some("f"), originalRequest = Some(orig))
      val out  = P.withResponseTiming(r, newTime, newIt)
      out shouldBe a[GetItemResponse]
      out.eventTime.ticks shouldBe 7L
      out.intraTick shouldBe (0.42 +- 1e-9)
      val g = out.asInstanceOf[GetItemResponse]
      g.itemFound shouldBe true
      g.itemBytes shouldBe Some(100L)
      g.flowId shouldBe Some("f")
      g.originalRequest shouldBe Some(orig)
    }

    "restamp a PutItemResponse and preserve payload" in {
      val r   = PutItemResponse(SimTime.of(2L), "uc", storedItemBytes = 300L, createdNewItem = true,
                                previousItemBytes = None)
      val out = P.withResponseTiming(r, newTime, newIt)
      out shouldBe a[PutItemResponse]
      out.eventTime.ticks shouldBe 7L
      val p = out.asInstanceOf[PutItemResponse]
      p.storedItemBytes shouldBe 300L
      p.createdNewItem shouldBe true
    }

    "restamp a DeleteItemResponse" in {
      val r   = DeleteItemResponse(SimTime.of(2L), "uc", deletedItemBytes = Some(50L))
      val out = P.withResponseTiming(r, newTime, newIt)
      out shouldBe a[DeleteItemResponse]
      out.eventTime.ticks shouldBe 7L
      out.intraTick shouldBe (0.42 +- 1e-9)
      out.asInstanceOf[DeleteItemResponse].deletedItemBytes shouldBe Some(50L)
    }
  }
