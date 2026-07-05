package stochastacy.aws.dynamodb.client

import scala.concurrent.duration.{Duration, FiniteDuration, *}
import stochastacy.aws.dynamodb.*

/** Configuration for the retry-and-backoff behaviour of a simulated AWS SDK
 *  client sitting between a workload and a DynamoDB service.
 *
 *  Matches the standard-mode retry policy shape shared by AWS Java SDK v2,
 *  boto3, and the AWS Go/C++ SDKs: a bounded number of attempts, exponential
 *  backoff with configurable jitter, and a classifier that decides which
 *  response types should be retried.
 *
 *  @param maxAttempts     Total attempts including the initial request. E.g.,
 *                         `maxAttempts = 3` allows one initial attempt plus up
 *                         to two retries. Must be >= 1; `1` means "no retries".
 *  @param baseBackoff     Base delay before the first retry (attempt 1). The
 *                         nominal delay before attempt `n` is `baseBackoff * 2^(n-1)`,
 *                         capped at `maxBackoff`, then randomised per `jitter`.
 *  @param maxBackoff      Upper cap on any single retry delay. AWS SDKs cap at
 *                         ~20 seconds by convention.
 *  @param jitter          Randomisation strategy applied to each delay.
 *  @param retryProportion Fraction of retryable failures that actually get retried.
 *                         Defaults to `1.0` (every retryable failure is retried).
 *                         Lower values model clients that give up early (e.g.,
 *                         because of `apiCallTimeout` expiry or misconfiguration).
 *  @param retryable       Classifier deciding whether a response should trigger a
 *                         retry attempt. Defaults to `AwsDefaultRetryable`, which
 *                         retries throttles and transient server errors only. */
final case class SdkRetryStrategy(
  maxAttempts:     Int,
  baseBackoff:     FiniteDuration,
  maxBackoff:      FiniteDuration,
  jitter:          JitterStrategy = JitterStrategy.Equal,
  retryProportion: Double         = 1.0,
  retryable:       DynamoDBResponse => Boolean = SdkRetryStrategy.AwsDefaultRetryable
):
  require(maxAttempts >= 1,
    s"maxAttempts must be >= 1 (a value of 1 means no retries — initial attempt only), got $maxAttempts")
  require(baseBackoff > Duration.Zero,
    s"baseBackoff must be positive, got $baseBackoff")
  require(maxBackoff >= baseBackoff,
    s"maxBackoff ($maxBackoff) must be >= baseBackoff ($baseBackoff)")
  require(retryProportion >= 0.0 && retryProportion <= 1.0,
    s"retryProportion must be in [0.0, 1.0], got $retryProportion")

object SdkRetryStrategy:

  /** AWS Java SDK v2 "standard" retry mode: 3 attempts total, 100ms base, 20s
   *  cap, equal-jitter backoff. This is the default when constructing a
   *  `DynamoDbClient` without overrides. */
  val awsJavaSdkV2Standard: SdkRetryStrategy = SdkRetryStrategy(
    maxAttempts = 3,
    baseBackoff = 100.millis,
    maxBackoff  = 20.seconds,
    jitter      = JitterStrategy.Equal
  )

  /** boto3 "standard" retry mode: 3 attempts total, 100ms base, 20s cap,
   *  full-jitter backoff. This is the default for boto3 clients configured
   *  with `retries = {'mode': 'standard'}`. */
  val boto3Standard: SdkRetryStrategy = SdkRetryStrategy(
    maxAttempts = 3,
    baseBackoff = 100.millis,
    maxBackoff  = 20.seconds,
    jitter      = JitterStrategy.Full
  )

  /** Default retryable-response classifier, matching typical AWS SDK behaviour:
   *  retry throttling and transient server errors; do NOT retry validation
   *  rejections, admission-policy failures, or successful responses. */
  val AwsDefaultRetryable: DynamoDBResponse => Boolean = {
    case _: ThrottledResponse    => true
    case _: SystemErrorResponse  => true
    case _                       => false
  }
