package stochastacy.test

import org.apache.pekko.stream.testkit.TestSubscriber
import stochastacy.aws.dynamodb.*
import stochastacy.sim.TimedEvent

extension [T](probe: TestSubscriber.Probe[T])

  def assertEmptyStream(): Unit =
    probe.expectSubscription()
    probe.expectComplete()

/** Zero out the `intraTick` field AND `originalRequest` reference of any DynamoDB
 *  response, leaving non-response timed events unchanged.  Use at comparison sites
 *  in tests that care about response content but not sub-tick timing or the
 *  originating-request reference (which is added for `SdkClientStage` retry
 *  reconstruction).
 */
extension (e: TimedEvent)
  def clearTiming: TimedEvent = e match
    case r: GetItemResponse                         => r.copy(intraTick = 0.0, originalRequest = None)
    case r: PutItemResponse                         => r.copy(intraTick = 0.0, originalRequest = None)
    case r: UpdateItemResponse                      => r.copy(intraTick = 0.0, originalRequest = None)
    case r: DeleteItemResponse                      => r.copy(intraTick = 0.0, originalRequest = None)
    case r: QueryResponse                           => r.copy(intraTick = 0.0, originalRequest = None)
    case r: ScanResponse                            => r.copy(intraTick = 0.0, originalRequest = None)
    case r: PartiQLQueryResponse                    => r.copy(intraTick = 0.0, originalRequest = None)
    case r: ThrottledResponse                       => r.copy(intraTick = 0.0, originalRequest = None)
    case r: ItemCollectionSizeLimitExceededResponse => r.copy(intraTick = 0.0, originalRequest = None)
    case r: SystemErrorResponse                     => r.copy(intraTick = 0.0, originalRequest = None)
    case r: BoundaryTimeoutResponse                 => r.copy(intraTick = 0.0, originalRequest = None)
    case r: ReconfigurationRejectedResponse         => r.copy(intraTick = 0.0, originalRequest = None)
    case r: TransactWriteItemsResponse              => r.copy(intraTick = 0.0, originalRequest = None)
    case r: TransactGetItemsResponse                => r.copy(intraTick = 0.0, originalRequest = None)
    case other                                      => other
