package stochastacy.test

import org.apache.pekko.stream.testkit.TestSubscriber
import stochastacy.aws.dynamodb.*
import stochastacy.sim.TimedEvent

extension [T](probe: TestSubscriber.Probe[T])

  def assertEmptyStream(): Unit =
    probe.expectSubscription()
    probe.expectComplete()

/** Zero out the `intraTick` field of any DynamoDB response, leaving non-response
 *  timed events unchanged.  Use at comparison sites in tests that care about
 *  response content but not sub-tick timing.
 */
extension (e: TimedEvent)
  def clearTiming: TimedEvent = e match
    case r: GetItemResponse                         => r.copy(intraTick = 0.0)
    case r: PutItemResponse                         => r.copy(intraTick = 0.0)
    case r: UpdateItemResponse                      => r.copy(intraTick = 0.0)
    case r: DeleteItemResponse                      => r.copy(intraTick = 0.0)
    case r: QueryResponse                           => r.copy(intraTick = 0.0)
    case r: ScanResponse                            => r.copy(intraTick = 0.0)
    case r: PartiQLQueryResponse                    => r.copy(intraTick = 0.0)
    case r: ThrottledResponse                       => r.copy(intraTick = 0.0)
    case r: ItemCollectionSizeLimitExceededResponse => r.copy(intraTick = 0.0)
    case r: SystemErrorResponse                     => r.copy(intraTick = 0.0)
    case r: ReconfigurationRejectedResponse         => r.copy(intraTick = 0.0)
    case r: TransactWriteItemsResponse              => r.copy(intraTick = 0.0)
    case r: TransactGetItemsResponse                => r.copy(intraTick = 0.0)
    case other                                      => other
