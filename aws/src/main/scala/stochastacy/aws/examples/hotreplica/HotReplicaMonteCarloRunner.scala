package stochastacy.aws.examples.hotreplica

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Sink

import stochastacy.core.run.MonteCarlo

/** Cross-trial mean of one region's roll-up. Cost is capacity + storage only — global-table replication
 *  transfer is free in AWS, so there is no transfer cost. */
final case class RegionAggregate(
  regionName:             String,
  meanRcu:                Double,
  meanWcu:                Double,
  meanRwcu:               Double,
  meanFinalStorageBytes:  Double,
  meanThrottledRequests:  Double,
  meanTotalCost:          BigDecimal
)

/** Cross-trial mean of one `(source → dest)` link's replication metrics. */
final case class LinkAggregate(
  sourceRegion:    String,
  destRegion:      String,
  meanTransferBytes: Double,
  meanLatencyMean: Double,
  meanLatencyP95:  Double,
  meanLatencyMax:  Double,
  meanPendingMean: Double,
  meanPendingMax:  Double
)

/** The ensemble result: per-region and per-link cross-trial means, plus the **first trial's** per-tick link
 *  series (a bounded sample for the streaming JSONL — the depletion story a single representative trace). */
final case class HotReplicaResult(
  scenarioId:  String,
  trialCount:  Int,
  regions:     Vector[RegionAggregate],
  links:       Vector[LinkAggregate],
  sampleLinks: Vector[LinkSummary]
)

/**
 * Runs a [[HotReplicaConfig]] as a Monte Carlo ensemble and folds the trials into cross-trial means
 * **incrementally** (`MonteCarlo.stream` + `Sink.fold`), so the trials never all sit in memory. The first
 * trial's per-tick link series is retained as the JSONL sample.
 */
final class HotReplicaMonteCarloRunner()(using system: ActorSystem, mat: Materializer, ec: ExecutionContext):

  def run(config: HotReplicaConfig, masterSeed: Long): Future[HotReplicaResult] =
    val runner = new HotReplicaTrialRunner()
    MonteCarlo.stream(config.trialCount, masterSeed, config.parallelism)(seed => runner.runTrial(config, 0, seed))
      .runWith(Sink.fold(new Aggregator(config)) { (agg, trial) => agg.add(trial); agg })
      .map(_.result())

  /** The mutable cross-trial accumulator: running sums by region and by link, dividing by the trial count at
   *  the end; keeps the first trial's per-tick series as the JSONL sample. */
  private final class Aggregator(config: HotReplicaConfig):
    private var count = 0
    private val rcu, wcu, rwcu, storage, throttled = mutable.Map.empty[String, Double].withDefaultValue(0.0)
    private val capStoreCost                        = mutable.Map.empty[String, BigDecimal].withDefaultValue(BigDecimal(0))
    private val linkBytes, linkLatMean, linkLatP95, linkLatMax, linkPendMean, linkPendMax =
      mutable.Map.empty[(String, String), Double].withDefaultValue(0.0)
    private var sample: Vector[LinkSummary] = Vector.empty

    def add(t: HotReplicaTrialResult): Unit =
      count += 1
      if count == 1 then sample = t.links
      for r <- t.regions do
        rcu(r.regionName)       += r.totalRcu.toDouble
        wcu(r.regionName)       += r.totalWcu.toDouble
        rwcu(r.regionName)      += r.totalRwcu.toDouble
        storage(r.regionName)   += r.finalStorageBytes.toDouble
        throttled(r.regionName) += r.throttledRequests.toDouble
        capStoreCost(r.regionName) = capStoreCost(r.regionName) + r.capacityAndStorageCost
      for l <- t.links do
        val k = (l.sourceRegion, l.destRegion)
        linkBytes(k)    += l.transferBytes.toDouble
        linkLatMean(k)  += l.latencyMean
        linkLatP95(k)   += l.latencyP95
        linkLatMax(k)   += l.latencyMax.toDouble
        linkPendMean(k) += l.pendingMean
        linkPendMax(k)  += l.pendingMax.toDouble

    def result(): HotReplicaResult =
      val n  = math.max(1, count)
      val nb = BigDecimal(n)
      val regions = config.regionNames.map { r =>
        RegionAggregate(
          regionName            = r,
          meanRcu               = rcu(r) / n,
          meanWcu               = wcu(r) / n,
          meanRwcu              = rwcu(r) / n,
          meanFinalStorageBytes = storage(r) / n,
          meanThrottledRequests = throttled(r) / n,
          meanTotalCost         = capStoreCost(r) / nb
        )
      }
      val links = linkBytes.keys.toVector.sorted.map { k =>
        LinkAggregate(k._1, k._2, linkBytes(k) / n, linkLatMean(k) / n, linkLatP95(k) / n, linkLatMax(k) / n,
          linkPendMean(k) / n, linkPendMax(k) / n)
      }
      HotReplicaResult(config.scenarioId, count, regions, links, sample)
