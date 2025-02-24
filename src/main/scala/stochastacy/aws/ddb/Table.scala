package stochastacy.aws.ddb

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.{Flow, GraphDSL, Keep, Sink, Source, SourceQueueWithComplete}
import org.apache.pekko.stream.{Graph, Materializer, OverflowStrategy, SourceShape}
import stochastacy.aws.ddb.Table.{PerformanceProfile, ResourceConsumptionEvents}
import stochastacy.{TimeWindow, TimeWindowedEvents}

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Random

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
  /**
   * Constructs a graph with input and output sources defined by calls to `useCaseInteractionsN` defined
   * in `TableN` classes that mix in this trait.
   */
  def constructGraph: Source[Table.ResourceConsumptionEvents, NotUsed]

class Table1[Reqs <: Table.RequestInteractions, Resps <: Table.ResponseInteractions](initialState: Table.TableState, provisioning: Table.TableProvisioning)(using ec: ExecutionContext) extends Table:
  type InteractionTuple1 = (Source[Reqs, NotUsed], PerformanceProfile[Reqs, Resps], Promise[Source[Resps, NotUsed]])

  /** threadsafety: only updated during calls to `useCaseInteractionsN`; those methods are explicitly _not_
   *  threadsafe because of this. */
  private var inter1_opt: Option[InteractionTuple1] = None

  /**
   * Note _NOT_ threadsafe. Should be called by client code only once during configuration of the table, prior
   * to calling `constructGraph`
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
  protected def useCaseInteractions1(reqsSource: Source[Reqs, NotUsed],
                                         perfProfile: Table.PerformanceProfile[Reqs, Resps]): Source[Resps, NotUsed] =
    val respsPromise = Promise[Source[Resps, NotUsed]]()
    inter1_opt = Some((reqsSource, perfProfile, respsPromise))
    Source.futureSource(respsPromise.future).mapMaterializedValue(_ => NotUsed)


  /**
   * Construct the table's performance graph.
   *
   * @param materializer The `Materializer` is necessary to convey per-use-case performance data, which is
   *                     conveyed to the `Source`s returned from the `useCaseInteractionsN` methods.
   * @return The `Source[Table.ResourceConsumptionEvents]` returned from this method would be wired into
   *         a larger graph, and would convey the resources consumed by the table as a whole during
   *         a simulation. This would include resources consumed by read and write queries, as well as
   *         resources consumed by the table for data storage and other background activities.
   */
  override def constructGraph: Source[Table.ResourceConsumptionEvents, NotUsed] =
    var graphTableState = initialState

    // ...This is the central kernel of the table interaction simulation: a single Reqs emitted from the
    //    inter1 source represents the requests received within a single time window. This graph simulates
    //    executing those requests one-by-one, parcelling each out to a 1-second window statistically.
    //
    // ...The per-interaction Sources are implemented as a chain of Futures. In each time window a set of
    //    resps is computed; at the end of this computation a future is completed with the accumulated resps
    //    value; this causes the associated source to emit the resps. At the concluion of this flow, the
    //    final future is completed with a None value, closing the associated Source...
    val graph = GraphDSL.create() { implicit builder =>
      import GraphDSL.Implicits.*

      val reqsSource = inter1_opt.get._1
      val perf = inter1_opt.get._2
      val respsSourcePromise = inter1_opt.get._3

      var nextRespsPromise = Promise[Option[Resps]]
      val respsSource: Source[Resps, NotUsed] = Source.unfoldAsync[NotUsed, Resps](null)(_ => nextRespsPromise.future.map(_.map((null, _))))
      respsSourcePromise.completeWith(Future.successful(respsSource))

      val simulationFlow = Flow[Reqs].map({ req =>
        val secs = (req.window.windowSize.millis / 1000).toInt
        val initialSec = req.window.windowStart / 1000
        val requestBuckets = Table.simulateArrivals(req.requestsCnt, secs)
        val resourceConsumptionEvents = new ResourceConsumptionEvents:
          override val window: TimeWindow = req.window

        val resps = perf.freshResps

        // ...each second update read unit budget & simulate execution of all requests within that second,
        //    updating read unit budget with each request...
        for sec <- 0 to secs do
          val clockTimeSec = initialSec + sec
          provisioning.tick(clockTimeSec)
          for r <- 0 to requestBuckets(sec) do
            // * find what resources would be required to successfully complete request
            val resourceRequirements = perf.resourcesRequiredForUseCaseExecution(graphTableState, clockTimeSec)

            // * consume resources if available, or throttle request
            val (newTableState, resourceConsumption) = provisioning.simulateExecution(graphTableState, clockTimeSec, resourceRequirements)
            graphTableState = newTableState
            // ...accrue resource consumption in per-interaction resps and overall resourceConsumptionEvents...
            resps.accrue(resourceConsumption)
            resourceConsumptionEvents.accrue(resourceConsumption)

        // ...send per-interaction resps to appropriate sources through the promise waiting for resps...
        val tmp = nextRespsPromise
        nextRespsPromise = Promise[Option[Resps]]
        tmp.completeWith(Future.successful(Some(resps)))

        resourceConsumptionEvents
      })

      // ...when the flow is complete, shut down all the per-interaction sources
      .watchTermination() { (_, doneFuture) =>
        doneFuture.onComplete(_ => nextRespsPromise.completeWith(Future.successful(None)))
      }

      val simulationFlowShape = builder.add(simulationFlow)
      reqsSource ~> simulationFlowShape.in

      // ...this graph's sole unbound port is the simulate flow's output, which is a source
      //    of ResourceConsumptionEvents objects...
      SourceShape(simulationFlowShape.out)
    }

    Source.fromGraph(graph)


object Table:

  sealed trait RequestInteractions extends TimeWindowedEvents:
    val requestsCnt: Long

  sealed trait ResponseInteractions extends TimeWindowedEvents:
    def accrue(resourceConsumption: ResourceConsumptionEvents): Unit = { /* placeholder */}

  trait GetItemRequestsLike extends RequestInteractions

  case class GetItemRequests(override val window: TimeWindow, override val requestsCnt: Long) extends GetItemRequestsLike

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

  trait PerformanceProfile[Reqs <: RequestInteractions, Resps <: ResponseInteractions]:
    def freshResps: Resps
    def resourcesRequiredForUseCaseExecution(state: TableState, clockTimeSec: Long): TableResources

  trait TableState

  trait TableProvisioning:
    def tick(clockTimeSec: Long): Unit
    def simulateExecution(graphTableState: TableState, clockTimeSec: Long, resourceRequirements: TableResources): (TableState, ResourceConsumptionEvents)

  case class TableResources(readUnits: Int)

  trait ResourceConsumptionEvents extends TimeWindowedEvents:
    def accrue(resourceConsumptionEvents: ResourceConsumptionEvents): Unit = {/* placeholder */}

  /**
   * Utility function `simulateArrivals` generates an array of `n` values randomly partitioned
   * into `m` buckets.
   **/
  // TODO: Needs to support Long n-values. Bucket count I don't think needs to ever get above seconds/24h == 86400
  def simulateArrivals(n: Long, m: Int): Array[Int] =
    val buckets = Array.fill[Int](m)(0)
    (1 to n.toInt).foreach { _ =>
      val bucket = (rnd_! * m).toInt
      buckets(bucket) += 1
    }
    buckets

  val random: Random = Random()
  def rnd_! : Double = random.nextDouble()