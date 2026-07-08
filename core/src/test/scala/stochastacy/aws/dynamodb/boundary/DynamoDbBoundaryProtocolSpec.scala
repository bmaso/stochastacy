package stochastacy.aws.dynamodb.boundary

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.boundary.BoundaryDropDirection
import stochastacy.aws.dynamodb.*
import stochastacy.aws.dynamodb.client.SdkRetryStrategy
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

  "timeoutResponse" should {

    "build a retryable BoundaryTimeoutResponse carrying the request" in {
      val req = GetItemRequest(SimTime.of(3L), "uc", flowId = Some("f"), clientAttempt = 2)
      val out = P.timeoutResponse(req, SimTime.of(3L), 0.25, BoundaryDropDirection.Ingress)
      out shouldBe a[BoundaryTimeoutResponse]
      val t = out.asInstanceOf[BoundaryTimeoutResponse]
      t.droppedDirection shouldBe BoundaryDropDirection.Ingress
      t.eventTime.ticks shouldBe 3L
      t.intraTick shouldBe (0.25 +- 1e-9)
      t.flowId shouldBe Some("f")
      t.clientAttempt shouldBe 2
      t.originalRequest shouldBe Some(req)
      SdkRetryStrategy.AwsDefaultRetryable(t) shouldBe true
    }

    "tag egress drops with the Egress direction" in {
      val req = PutItemRequest(SimTime.of(1L), "uc", itemBytes = 10L)
      val out = P.timeoutResponse(req, SimTime.of(1L), 0.0, BoundaryDropDirection.Egress)
      out.asInstanceOf[BoundaryTimeoutResponse].droppedDirection shouldBe BoundaryDropDirection.Egress
    }
  }

  "originalRequestOf" should {

    "return the originating request when present" in {
      val orig = GetItemRequest(SimTime.of(1L), "uc")
      val resp = GetItemResponse(SimTime.of(1L), "uc", itemFound = true, itemBytes = Some(1L),
                                 originalRequest = Some(orig))
      P.originalRequestOf(resp) shouldBe Some(orig)
    }

    "return None when absent" in {
      val resp = GetItemResponse(SimTime.of(1L), "uc", itemFound = false, itemBytes = None)
      P.originalRequestOf(resp) shouldBe None
    }
  }
