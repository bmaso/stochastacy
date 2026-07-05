package stochastacy.aws.dynamodb.client

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.*
import stochastacy.aws.dynamodb.table.DynamoDbTarget
import stochastacy.sim.SimTime

import scala.concurrent.duration.*

class SdkRetryStrategySpec extends AnyWordSpec with should.Matchers:

  private val t: SimTime            = SimTime.of(1L)
  private val tgt: DynamoDbTarget   = DynamoDbTarget.Table("t")
  private val op                     = DynamoDbOperationKind.GetItem
  private val dim                    = DynamoDbThroughputDimension.Read
  private val reason                 = DynamoDbThrottleReason.TableReadMaxOnDemandThroughputExceeded

  "SdkRetryStrategy validation" should {

    "reject maxAttempts < 1" in {
      an [IllegalArgumentException] should be thrownBy
        SdkRetryStrategy(maxAttempts = 0, baseBackoff = 100.millis, maxBackoff = 20.seconds)
    }

    "reject non-positive baseBackoff" in {
      an [IllegalArgumentException] should be thrownBy
        SdkRetryStrategy(maxAttempts = 3, baseBackoff = 0.millis, maxBackoff = 20.seconds)
    }

    "reject maxBackoff smaller than baseBackoff" in {
      an [IllegalArgumentException] should be thrownBy
        SdkRetryStrategy(maxAttempts = 3, baseBackoff = 200.millis, maxBackoff = 100.millis)
    }

    "reject retryProportion below 0" in {
      an [IllegalArgumentException] should be thrownBy
        SdkRetryStrategy(maxAttempts = 3, baseBackoff = 100.millis, maxBackoff = 20.seconds, retryProportion = -0.1)
    }

    "reject retryProportion above 1" in {
      an [IllegalArgumentException] should be thrownBy
        SdkRetryStrategy(maxAttempts = 3, baseBackoff = 100.millis, maxBackoff = 20.seconds, retryProportion = 1.1)
    }

    "accept maxAttempts == 1 (no retries)" in {
      val s = SdkRetryStrategy(maxAttempts = 1, baseBackoff = 100.millis, maxBackoff = 20.seconds)
      s.maxAttempts shouldBe 1
    }
  }

  "SdkRetryStrategy factory presets" should {

    "expose awsJavaSdkV2Standard with 3 attempts, 100ms base, 20s cap, equal jitter" in {
      val s = SdkRetryStrategy.awsJavaSdkV2Standard
      s.maxAttempts shouldBe 3
      s.baseBackoff shouldBe 100.millis
      s.maxBackoff  shouldBe 20.seconds
      s.jitter      shouldBe JitterStrategy.Equal
    }

    "expose boto3Standard with 3 attempts, 100ms base, 20s cap, full jitter" in {
      val s = SdkRetryStrategy.boto3Standard
      s.maxAttempts shouldBe 3
      s.baseBackoff shouldBe 100.millis
      s.maxBackoff  shouldBe 20.seconds
      s.jitter      shouldBe JitterStrategy.Full
    }

    "default retryProportion to 1.0 in presets" in {
      SdkRetryStrategy.awsJavaSdkV2Standard.retryProportion shouldBe 1.0
      SdkRetryStrategy.boto3Standard.retryProportion        shouldBe 1.0
    }
  }

  "AwsDefaultRetryable classifier" should {

    val classify = SdkRetryStrategy.AwsDefaultRetryable

    "retry ThrottledResponse" in {
      classify(ThrottledResponse(t, "uc", op, tgt, dim, reason)) shouldBe true
    }

    "retry SystemErrorResponse" in {
      classify(SystemErrorResponse(t, "uc", op, tgt)) shouldBe true
    }

    "not retry a successful GetItemResponse" in {
      classify(GetItemResponse(t, "uc", itemFound = true, itemBytes = Some(100L))) shouldBe false
    }

    "not retry ItemCollectionSizeLimitExceededResponse" in {
      classify(ItemCollectionSizeLimitExceededResponse(
        eventTime                = t,
        usecase                  = "uc",
        operation                = op,
        target                   = tgt,
        resultingCollectionBytes = 11L * 1024L * 1024L * 1024L,
        limitBytes               = 10L * 1024L * 1024L * 1024L
      )) shouldBe false
    }

    "not retry ReconfigurationRejectedResponse" in {
      classify(ReconfigurationRejectedResponse(t, "uc", "cooldown")) shouldBe false
    }
  }
