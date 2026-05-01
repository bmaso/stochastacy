package stochastacy.aws.dynamodb.table

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.{Broadcast, Flow, GraphDSL, Sink}
import org.apache.pekko.stream.{Graph, Outlet}
import stochastacy.aws.dynamodb.*
import stochastacy.aws.transfer.CrossRegionTransferEvent
import stochastacy.sim.{TimedControlEvent, TimedElement, TimedEvent}
import stochastacy.sim.stream.MergeTimedEventGraph

/**
 * A multi-region DynamoDB Global Table simulator. Composes N independent regional
 * `DynamoDbTable.componentOfReplicated` instances with a `ReplicationCoordinator` that
 * propagates writes between regions with stochastic per-link lag. Per-region cost data flows
 * out on per-region consumption/metric streams; cross-region data transfer cost flows out on
 * a single dedicated transfer-event stream (per design decisions 4 and 5).
 *
 * Per-region configs may include GSIs and LSIs. Index maintenance (write amplification, rWCU
 * accounting for index targets) runs at each destination region for replicated writes.
 */
object DynamoDbGlobalTable:

  final case class Config(
                           regions: Map[String, DynamoDbTable.Config],
                           replicationModel: ReplicationModel
                         ):
    require(regions.nonEmpty, "regions must be non-empty")

  def componentOf(config: Config): Graph[DynamoDbGlobalTableShape, NotUsed] =
    val regions: Vector[String] = config.regions.keys.toVector.sorted

    GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits.*

      // Build per-region replicated tables.
      val regionGraphs: Map[String, DynamoDbTableReplicatedShape] =
        regions.map(r => r -> b.add(DynamoDbTable.componentOfReplicated(config.regions(r)))).toMap

      // For each region, tag its outbound replication output with sourceRegion = r so the
      // coordinator can determine which region originated each event.
      val tagFlows: Map[String, org.apache.pekko.stream.FlowShape[
        TimedElement[AdmittedRequestSample],
        TimedElement[ReplicationCoordinator.OriginTaggedReplicationEvent]
      ]] =
        regions.map { r =>
          r -> b.add(
            Flow[TimedElement[AdmittedRequestSample]].map[TimedElement[ReplicationCoordinator.OriginTaggedReplicationEvent]] {
              case t: TimedControlEvent => t
              case sample: AdmittedRequestSample => ReplicationCoordinator.OriginTaggedReplicationEvent(r, sample)
            }
          )
        }.toMap

      // Wire each region's outbound port to its tagging flow.
      regions.foreach { r =>
        regionGraphs(r).outboundReplicationOut ~> tagFlows(r).in
      }

      // Merge all tagged outbound streams into a single coordinator-input stream. For N=1,
      // no merge needed: feed the single tagged source directly. For N>=2, chain N-1
      // MergeTimedEventGraph stages — this preserves tick alignment by pairing ticks across
      // input streams.
      //
      // The merger emits TimedEvent (the supertype); we coerce back to the tagged event type
      // before the coordinator's input.
      val coordinatorInputOutlet: Outlet[TimedEvent] =
        if regions.size == 1 then
          // Single region: just upcast the tagged source's output to TimedEvent.
          val identity = b.add(Flow[TimedElement[ReplicationCoordinator.OriginTaggedReplicationEvent]].map[TimedEvent](e => e))
          tagFlows(regions.head).out ~> identity.in
          identity.out
        else
          // Chain merges: ((s1 ⊕ s2) ⊕ s3) ⊕ s4 ⊕ ...
          val taggedOutlets: Vector[Outlet[TimedEvent]] = regions.map { r =>
            // Each tag flow's output upcast to TimedEvent for merging.
            val cast = b.add(Flow[TimedElement[ReplicationCoordinator.OriginTaggedReplicationEvent]].map[TimedEvent](e => e))
            tagFlows(r).out ~> cast.in
            cast.out
          }
          var accumulator: Outlet[TimedEvent] = taggedOutlets.head
          for next <- taggedOutlets.tail do
            val merge = b.add(MergeTimedEventGraph.graphOf(bufferSize = 16))
            accumulator ~> merge.in0
            next ~> merge.in1
            accumulator = merge.out
          accumulator

      // Coerce the merged TimedEvent stream back to TimedElement[OriginTaggedReplicationEvent]
      // so it matches the coordinator's input type.
      val coerceToTagged = b.add(
        Flow[TimedEvent].collect[TimedElement[ReplicationCoordinator.OriginTaggedReplicationEvent]] {
          case e: ReplicationCoordinator.OriginTaggedReplicationEvent => e
          case t: TimedControlEvent => t
        }
      )
      coordinatorInputOutlet ~> coerceToTagged.in

      // Build the coordinator.
      val coordinator = b.add(ReplicationCoordinator.flowOf(regions, config.replicationModel))
      coerceToTagged.out ~> coordinator.in

      // The coordinator's output is broadcast to N+1 consumers: one per destination region's
      // replicated-input port (filtering replicated writes destined for that region) plus one
      // for the transfer-events outlet.
      val coordinatorBroadcast = b.add(Broadcast[TimedElement[ReplicationCoordinator.ReplicationOutputEvent]](regions.size + 1))
      coordinator.out ~> coordinatorBroadcast.in

      // Per-region replicated-write filters: extract writes destined for the given region,
      // unwrap to AdmittedRequestSample, feed into that region's replicatedIn port.
      regions.zipWithIndex.foreach { case (r, idx) =>
        val perRegionFilter = b.add(
          Flow[TimedElement[ReplicationCoordinator.ReplicationOutputEvent]].collect[TimedElement[AdmittedRequestSample]] {
            case t: TimedControlEvent => t
            case w: ReplicationCoordinator.ReplicatedWriteForRegion if w.destinationRegion == r => w.sample
          }
        )
        coordinatorBroadcast.out(idx) ~> perRegionFilter.in
        perRegionFilter.out ~> regionGraphs(r).replicatedIn
      }

      // Transfer-events filter: extract CrossRegionTransferEvents from the coordinator's
      // mixed output. Drops the tick stream — the transfer events themselves carry eventTime,
      // which is sufficient for downstream usage/pricing aggregators.
      val transferFilter = b.add(
        Flow[TimedElement[ReplicationCoordinator.ReplicationOutputEvent]].collect[TimedElement[CrossRegionTransferEvent]] {
          case t: TimedControlEvent => t
          case ReplicationCoordinator.TransferEventOutput(e) => e
        }
      )
      coordinatorBroadcast.out(regions.size) ~> transferFilter.in

      new DynamoDbGlobalTableShape(
        regionRequestInlets = regions.map(r => r -> regionGraphs(r).requestIn).toMap,
        regionResponseOutlets = regions.map(r => r -> regionGraphs(r).responseOut).toMap,
        regionConsumptionOutlets = regions.map(r => r -> regionGraphs(r).consumptionOut).toMap,
        regionMetricOutlets = regions.map(r => r -> regionGraphs(r).metricOut).toMap,
        transferEventsOutlet = transferFilter.out
      )
    }

  def componentOfManaged(config: Config): Graph[DynamoDbGlobalTableManagedShape, NotUsed] =
    val regions: Vector[String] = config.regions.keys.toVector.sorted

    GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits.*

      val regionGraphs: Map[String, DynamoDbTableManagedReplicatedShape] =
        regions.map(r => r -> b.add(DynamoDbTable.componentOfManagedReplicated(config.regions(r)))).toMap

      val managementBroadcast = b.add(Broadcast[TimedElement[DynamoDbManagementEvent]](regions.size))

      val tagFlows: Map[String, org.apache.pekko.stream.FlowShape[
        TimedElement[AdmittedRequestSample],
        TimedElement[ReplicationCoordinator.OriginTaggedReplicationEvent]
      ]] =
        regions.map { r =>
          r -> b.add(
            Flow[TimedElement[AdmittedRequestSample]].map[TimedElement[ReplicationCoordinator.OriginTaggedReplicationEvent]] {
              case t: TimedControlEvent => t
              case sample: AdmittedRequestSample => ReplicationCoordinator.OriginTaggedReplicationEvent(r, sample)
            }
          )
        }.toMap

      regions.zipWithIndex.foreach { case (r, idx) =>
        regionGraphs(r).outboundReplicationOut ~> tagFlows(r).in
        managementBroadcast.out(idx) ~> regionGraphs(r).managementIn
      }

      val coordinatorInputOutlet: Outlet[TimedEvent] =
        if regions.size == 1 then
          val identity = b.add(Flow[TimedElement[ReplicationCoordinator.OriginTaggedReplicationEvent]].map[TimedEvent](e => e))
          tagFlows(regions.head).out ~> identity.in
          identity.out
        else
          val taggedOutlets: Vector[Outlet[TimedEvent]] = regions.map { r =>
            val cast = b.add(Flow[TimedElement[ReplicationCoordinator.OriginTaggedReplicationEvent]].map[TimedEvent](e => e))
            tagFlows(r).out ~> cast.in
            cast.out
          }
          var accumulator: Outlet[TimedEvent] = taggedOutlets.head
          for next <- taggedOutlets.tail do
            val merge = b.add(MergeTimedEventGraph.graphOf(bufferSize = 16))
            accumulator ~> merge.in0
            next ~> merge.in1
            accumulator = merge.out
          accumulator

      val coerceToTagged = b.add(
        Flow[TimedEvent].collect[TimedElement[ReplicationCoordinator.OriginTaggedReplicationEvent]] {
          case e: ReplicationCoordinator.OriginTaggedReplicationEvent => e
          case t: TimedControlEvent => t
        }
      )
      coordinatorInputOutlet ~> coerceToTagged.in

      val coordinator = b.add(ReplicationCoordinator.flowOf(regions, config.replicationModel))
      coerceToTagged.out ~> coordinator.in

      val coordinatorBroadcast = b.add(Broadcast[TimedElement[ReplicationCoordinator.ReplicationOutputEvent]](regions.size + 1))
      coordinator.out ~> coordinatorBroadcast.in

      regions.zipWithIndex.foreach { case (r, idx) =>
        val perRegionFilter = b.add(
          Flow[TimedElement[ReplicationCoordinator.ReplicationOutputEvent]].collect[TimedElement[AdmittedRequestSample]] {
            case t: TimedControlEvent => t
            case w: ReplicationCoordinator.ReplicatedWriteForRegion if w.destinationRegion == r => w.sample
          }
        )
        coordinatorBroadcast.out(idx) ~> perRegionFilter.in
        perRegionFilter.out ~> regionGraphs(r).replicatedIn
      }

      val transferFilter = b.add(
        Flow[TimedElement[ReplicationCoordinator.ReplicationOutputEvent]].collect[TimedElement[CrossRegionTransferEvent]] {
          case t: TimedControlEvent => t
          case ReplicationCoordinator.TransferEventOutput(e) => e
        }
      )
      coordinatorBroadcast.out(regions.size) ~> transferFilter.in

      new DynamoDbGlobalTableManagedShape(
        regionRequestInlets = regions.map(r => r -> regionGraphs(r).requestIn).toMap,
        managementIn = managementBroadcast.in,
        regionResponseOutlets = regions.map(r => r -> regionGraphs(r).responseOut).toMap,
        regionConsumptionOutlets = regions.map(r => r -> regionGraphs(r).consumptionOut).toMap,
        regionMetricOutlets = regions.map(r => r -> regionGraphs(r).metricOut).toMap,
        transferEventsOutlet = transferFilter.out
      )
    }
