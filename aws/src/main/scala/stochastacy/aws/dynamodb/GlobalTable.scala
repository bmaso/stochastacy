package stochastacy.aws.dynamodb

import scala.concurrent.Future

import org.apache.commons.rng.UniformRandomProvider
import org.apache.pekko.stream.{Graph, Inlet, Outlet, Shape}
import org.apache.pekko.stream.scaladsl.{Broadcast, Flow, GraphDSL}

import stochastacy.core.component.{ComponentResult, Timed}
import stochastacy.sim.stream.MergeTimedEventGraph
import stochastacy.sim.{TimedControlEvent, TimedElement, TimedEvent}

/** The stream shape of a multi-region [[GlobalTable]]: a request inlet and response/consumption outlets **per
 *  region**, plus one replication-metrics outlet (cross-region transfer + `ReplicationLatency` +
 *  `PendingReplicationCount`). The per-region tables replicate each other's writes internally. */
final class GlobalTableShape(
  val requestIn:      Map[String, Inlet[TimedElement[Timed[DynamoDbRequest]]]],
  val responseOut:    Map[String, Outlet[TimedElement[Timed[DynamoDbResponse]]]],
  val consumptionOut: Map[String, Outlet[TimedElement[Timed[DynamoDbConsumption]]]],
  val metricsOut:     Outlet[TimedElement[Timed[ReplicationOutput]]]
) extends Shape:
  private def sortedKeys[V](m: Map[String, V]): Seq[V] = m.toVector.sortBy(_._1).map(_._2)
  override def inlets:  Seq[Inlet[?]]  = sortedKeys(requestIn)
  override def outlets: Seq[Outlet[?]] = sortedKeys(responseOut) ++ sortedKeys(consumptionOut) :+ metricsOut
  override def deepCopy(): GlobalTableShape = new GlobalTableShape(
    requestIn.view.mapValues(_.carbonCopy()).toMap,
    responseOut.view.mapValues(_.carbonCopy()).toMap,
    consumptionOut.view.mapValues(_.carbonCopy()).toMap,
    metricsOut.carbonCopy()
  )

/**
 * A multi-region DynamoDB **Global Table**: N regional [[DynamoDbTable]]s that replicate each other's writes.
 * Each region is a loopback component (`replicatedComponentOf`); a [[ReplicationCoordinator]] taps every
 * region's admitted writes, delays them per link, and routes them to the peer regions' feedback inputs, where
 * they re-apply billing rWCU. The region↔coordinator cycle is deadlock-free by the loopback transducer's eager
 * tap-tick forwarding (phase-11 Slice 1). Per-region cost data flows out on the per-region consumption outlets;
 * cross-region transfer + replication metrics flow out on the single `metricsOut`.
 */
object GlobalTable:

  final case class Config(regions: Map[String, DynamoDbTable.Config], replicationModel: ReplicationModel):
    require(regions.nonEmpty, "regions must be non-empty")

  def componentOf(config: Config, rng: UniformRandomProvider): Graph[GlobalTableShape, Map[String, Future[ComponentResult[TableState]]]] =
    val regions: Vector[String] = config.regions.keys.toVector.sorted
    val regionGraphs = regions.map(r => DynamoDbTable.replicatedComponentOf(config.regions(r), rng))

    val graph = GraphDSL.create(regionGraphs) { implicit b => shapes =>
      import GraphDSL.Implicits.*
      val ls = regions.zip(shapes).toMap

      // Tag each region's tap stream with its source region and merge all into the coordinator input.
      val tagged: Vector[Outlet[TimedEvent]] = regions.map { r =>
        val tag = b.add(Flow[TimedElement[Timed[ReplicationWrite]]].map[TimedEvent] {
          case c: TimedControlEvent => c
          case Timed(w, et, it, uc) => Timed(TaggedTap(r, w), et, it, uc)
        })
        ls(r).tapOut ~> tag.in
        tag.out
      }
      var merged: Outlet[TimedEvent] = tagged.head
      for next <- tagged.tail do
        val m = b.add(MergeTimedEventGraph.graphOf(bufferSize = 16))
        merged ~> m.in0; next ~> m.in1; merged = m.out
      val coerce = b.add(Flow[TimedEvent].collect[TimedElement[Timed[TaggedTap]]] {
        case c: TimedControlEvent           => c
        case t: Timed[TaggedTap] @unchecked => t
      })
      merged ~> coerce.in

      val coord = b.add(ReplicationCoordinator.flow(regions, config.replicationModel, rng))
      coerce.out ~> coord.in

      val bcast = b.add(Broadcast[TimedElement[Timed[ReplicationOutput]]](regions.size + 1))
      coord.out ~> bcast.in

      // Route each destination's replicated writes into its feedback input.
      regions.zipWithIndex.foreach { case (r, i) =>
        val route = b.add(Flow[TimedElement[Timed[ReplicationOutput]]].collect[TimedElement[Timed[ReplicationWrite]]] {
          case c: TimedControlEvent                                                            => c
          case Timed(ReplicationOutput.ReplicatedWriteFor(dst, w), et, it, uc) if dst == r     => Timed(w, et, it, uc)
        })
        bcast.out(i) ~> route.in
        route.out ~> ls(r).fbIn
      }
      // The remaining broadcast leg carries transfer + latency + pending out on metricsOut.
      val metrics = b.add(Flow[TimedElement[Timed[ReplicationOutput]]].collect[TimedElement[Timed[ReplicationOutput]]] {
        case c: TimedControlEvent                                                          => c
        case t: Timed[ReplicationOutput] @unchecked if !t.event.isInstanceOf[ReplicationOutput.ReplicatedWriteFor] => t
      })
      bcast.out(regions.size) ~> metrics.in

      new GlobalTableShape(
        requestIn      = regions.map(r => r -> ls(r).in).toMap,
        responseOut    = regions.map(r => r -> ls(r).fwdOut).toMap,
        consumptionOut = regions.map(r => r -> ls(r).consOut).toMap,
        metricsOut     = metrics.out
      )
    }
    graph.mapMaterializedValue(mats => regions.zip(mats).toMap)
