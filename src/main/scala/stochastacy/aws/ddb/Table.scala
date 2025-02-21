package stochastacy.aws.ddb

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.{Graph, SourceShape}
import org.apache.pekko.stream.scaladsl.Source
import stochastacy.{TimeWindow, TimeWindowedEvents}

/**
 * A `Table` is a graph builder that builds a graph simulating the resource consumption of
 * a DynamoDB (DDB) table. Once the table is full constructed and configured, the constructGraph
 * method geneates a `Source[ResourceConsumptionEvents]`. The source object emits a stream of elements
 * representing the AWS resources consumed within individual time windows.
 * 
 * In an actual DDB table, the resource consumption behavior each time window depends on the table's
 * provisioning, and on the history or read and write operations submitted to the table since the
 * table was created. The factors the affect resource consumption at each time window is comprised of
 * these constituents:
 *
 * 1. **The contents of the table at operation time**. A Scan operation on an empty table
 *   consumes far fewer read units than on a multi-Tb table.
 * 1. **The table provisioning**. Tables may have OnDemand provisioning, or may be provisioned with a certain
 *   number of read units and write units.
 * 1. **The recently submitted and executed operations on the table _highly_ affects the table's throttling
 *   behavior and read/write unit consumption behavior**. An OnDemand table throttles requests when read unit
 *   consumption varies wildly within a very short amount of time, or exceeds the account maximum for the
 *   AWS region. A provisioned table will be throttled when read unit consumption rises above the provisioned
 *   level for significant amount of time. Several read and write unit documented restrictions can affect table
 *   throttling behavior.
 *
 * ## Stochastic modeling of table state and interaction use-cases
 *
 * Rather than trying to maintain a representation of a table's actual contents a `Table` instance models
 * table contents stochastically. There are a fixed and finite number of use-cases requiring interactions
 * with the table in the types of real-world systems we want this library to simulate. Each
 * use-case has its own performance profile, which is modeled stochastically.
 *
 * For example, imagine a table that maintains information about a fleet of IoT devices: the table
 * has one record per device, indexed on a unique deviceId. Each device has a location identifier string,
 * which encodes a hierarchy of more and more specific location indicators from right-to-left (eg,
 * "us-california-orange_county-capo_beach", used as a key in a secondary index. The table also maintains
 * an "active/inactive" flag and a timestamp when the device last contacted a central server.
 *
 * One use-case for interacting with this table is simple `GetItem` interaction, which retrieves the device
 * record for a deviceId. Instances of this interaction type successfully retrieve a record 1-2k in size
 * 99% of the time in our imagined system, irrespective of the number of devices stored in the table. We
 * model this simple use-case with a binomial distribution with a $p$ value of 0.99.
 *
 * Another use case for this table is a query for all the active IoT devices in a local area. The number
 * of read units consumed by a query in this use-case depends on the size of the table, which may
 * grow over time as more devices are added to the table. We can imagine the number of records
 * associated with each locality varies. To model this case we might choose to maintain some estimate
 * of the record count within the table as part of the table state. Further we would use real-world
 * observations to construct a statistical model of read units consumed per interaction as a function of
 * table size, or we might make an educated wish that the read units consumed by this interaction type
 * follows a Poisson or possibly Beta-Poisson distribution, with the $\beta$ and $\lambda$ parameters
 * dependent on the table record count estimation.
 *
 * ## AWS table provisioning
 *
 * The resources consumed by interactions are highly dependent on table provisioning within AWS. Tables
 * may be OnDemand, or be provisioned with a specific number of read and write units. OnDemand has higher
 * cost per read and write unit, but doesn't suffer usage throttling. Provisioning a table reduces
 * per read unit and write unit costs, but does introduce throttling. This `Table` class includes
 * an implementation of DDB's throttling logic, including DDB's "boost capacity" feature, which
 * avoids throttling when the table experiences short-term interaction traffic spikes.
 *
 * DDB auto-scaling, another feature that can be turned on for a Table during provisioned, will automatically
 * raise and lower a table's read and write unit provisioning to target a specific utilization level. This library
 * includes an implementation of the AWS auto-scaling algorithm.
 */
trait Table:
  def constructGraph: Graph[SourceShape[Table.ResourceConsumptionEvents], NotUsed]

class Table1[Reqs <: Table.RequestInteractions, Resps <: Table.ResponseInteractions, TableState](initialState: TableState, provisioning: Table.TableProvisioning) extends Table:

  /**
   * 
   * @param eventSource Elements emitted by the `Source` describe DDB GetItem interactions submitted within the
   *                    same time window
   * @param perfProfile A statistical model of the read units, latency, and retrieved data consumed by
   *                        GetItem interactions
   * @tparam Reqs Class-dependent type representing a collection of GetItem interaction requests for one table use-case.
   * @tparam Resps Class-dependent type representing a collection of GetItem interaction responses for one table use-case.
   * @tparam Mat Materialized type of the `eventSource`
   * @return A `Source` of GetItem interaction response collections
   */
  protected def useCaseInteractions1[Mat](reqsSource: Source[Reqs, Mat],
                                         perfProfile: Table.PerformanceProfile[Reqs, Resps]): Source[Resps, Mat]
  
  
object Table:
  
  sealed trait RequestInteractions extends TimeWindowedEvents
  sealed trait ResponseInteractions extends TimeWindowedEvents
  
  trait GetItemRequestsLike extends RequestInteractions:
    val requestCnt: Long

  case class GetItemRequests(override val window: TimeWindow, override val requestCnt: Long) extends GetItemRequestsLike
  
  trait GetItemResponsesLike extends ResponseInteractions:
    val requestHitCnt: Long
    val requestHitReadUnits: Long
    val requestHitBytes: Long
    val requestMissCnt: Long
    val requestFailureCnt: Long
    val totalLatencyMs: Long

  case class GetItemResponses(override val window: TimeWindow,
                              override val requestHitCnt: Long,
                              override val requestHitReadUnits: Long,
                              override val requestHitBytes: Long,
                              override val requestMissCnt: Long,
                              override val requestFailureCnt: Long,
                              override val totalLatencyMs: Long) extends GetItemResponsesLike:
    /** DDB debits at least 1 read unit per request */
    val consumedReadUnits: Long = requestHitReadUnits + requestMissCnt

  trait PerformanceProfile[Reqs <: RequestInteractions, Resps <: ResponseInteractions]

  trait TableProvisioning
  
  trait ResourceConsumptionEvents extends TimeWindowedEvents
  
