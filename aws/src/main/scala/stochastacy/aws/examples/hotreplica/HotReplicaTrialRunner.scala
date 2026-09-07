package stochastacy.aws.examples.hotreplica

import scala.concurrent.{ExecutionContext, Future}

import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, Materializer}
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}

import stochastacy.aws.dynamodb.{DynamoDbConsumption, DynamoDbRequest, DynamoDbResponse, GlobalTable, ReplicationOutput}
import stochastacy.core.component.Timed
import stochastacy.core.run.SeedSequence
import stochastacy.core.stream.TickFraming
import stochastacy.sim.{TimedElement, TimedControlEvent}

/** One trial's outcome: the per-region roll-ups, the per-link replication metrics, and the per-region
 *  cross-region egress **bytes** (replication volume — a metric only; AWS charges nothing for global-table
 *  replication transfer, so there is no transfer cost). */
final case class HotReplicaTrialResult(
  trialId:             Int,
  regions:             Vector[RegionSummary],
  links:               Vector[LinkSummary],
  regionTransferBytes: Map[String, Long]
):
  def region(name: String): RegionSummary          = regions.find(_.regionName == name).get
  def link(src: String, dst: String): LinkSummary  = links.find(l => l.sourceRegion == src && l.destRegion == dst).get
  def totalCost(name: String): BigDecimal          = region(name).capacityAndStorageCost

/**
 * Runs one trial of a [[HotReplicaConfig]] on a **bespoke** multi-region graph around [[GlobalTable]] — the
 * three regional telemetry tables replicate each other's writes through the coordinator (the cyclic,
 * deadlock-free loopback). Each region's workload is framed and fed to its `requestIn`; each region's
 * consumption plane is folded into a [[RegionAccountingState]], and the single `metricsOut` into a
 * [[ReplicationMetricsState]]. The Global Table's own per-region result futures are ignored — the sinks
 * carry everything.
 *
 * The graph is materialized with a 3-region + metrics `createGraph` (the demo is inherently three regions).
 */
final class HotReplicaTrialRunner()(using ActorSystem, Materializer, ExecutionContext):

  def runTrial(config: HotReplicaConfig, trialId: Int, seed: Long): Future[HotReplicaTrialResult] =
    val names = config.regionNames
    require(names.size == 3, s"the hot-replica runner composes exactly three regions, got ${names.size}")

    // seeds: one workload seed per region + one shared table/coordinator seed
    val seeds       = SeedSequence.derive(seed, names.size + 1)
    val tableSeed   = seeds.last
    val workloadSeed = names.zip(seeds).toMap

    // Per-region framed workloads (self-contained horizons framed to the shared tick count).
    val framed: Map[String, Vector[TimedElement[Timed[DynamoDbRequest]]]] = names.map { r =>
      val arrivals = config.region(r).thermostat(config.simulationTicks).arrivals(RandomSource.KISS.create(workloadSeed(r)))
      r -> TickFraming.frame(arrivals.iterator, config.simulationTicks).toVector
    }.toMap

    val gt = GlobalTable.componentOf(config.globalTableConfig, RandomSource.KISS.create(tableSeed))

    def regionSink(r: String): Sink[TimedElement[Timed[DynamoDbConsumption]], Future[RegionAccountingState]] =
      Sink.fold(new RegionAccountingState(r, config.region(r).billingMode, config.region(r).rates, config.simulationTicks)) {
        (state, element: TimedElement[Timed[DynamoDbConsumption]]) => state.update(element); state
      }
    val metricsSink: Sink[TimedElement[Timed[ReplicationOutput]], Future[ReplicationMetricsState]] =
      Sink.fold(new ReplicationMetricsState()) {
        (state, element: TimedElement[Timed[ReplicationOutput]]) => state.update(element); state
      }

    val (r0, r1, r2) = (names(0), names(1), names(2))
    val graph = RunnableGraph.fromGraph(
      GraphDSL.createGraph(regionSink(r0), regionSink(r1), regionSink(r2), metricsSink)((a0, a1, a2, m) => (a0, a1, a2, m)) {
        implicit b => (s0, s1, s2, sm) =>
          import GraphDSL.Implicits.*
          val g          = b.add(gt)
          val regionSinks = Map(r0 -> s0, r1 -> s1, r2 -> s2)
          for r <- names do
            b.add(Source(framed(r))) ~> g.requestIn(r)
            g.responseOut(r)    ~> b.add(Sink.ignore)
            g.consumptionOut(r) ~> regionSinks(r)
          g.metricsOut ~> sm
          ClosedShape
      }
    )

    val (a0F, a1F, a2F, mF) = graph.run()
    for
      a0 <- a0F; a1 <- a1F; a2 <- a2F; m <- mF
    yield
      val regionSummaries = Vector(a0, a1, a2).map(_.result())
      val links           = m.result()
      // Egress bytes by source region — the replication volume (a metric only; no transfer charge).
      val transferBytes = names.map(r => r -> links.filter(_.sourceRegion == r).map(_.transferBytes).sum).toMap
      HotReplicaTrialResult(trialId, regionSummaries, links, transferBytes)
