package stochastacy.aws.examples.ordertracking

import scala.collection.mutable

import org.apache.commons.rng.UniformRandomProvider

import stochastacy.aws.dynamodb.{DeleteItemRequest, DynamoDbRequest, DynamoDbTarget, GetItemRequest, PutItemRequest, QueryRequest, ReadConsistency, ScanRequest, UpdateItemRequest}
import stochastacy.core.component.Timed
import stochastacy.core.sampler.{PoissonSampler, UniformSampler}
import stochastacy.sim.SimTime

/**
 * The Phase-1 arrivals generator — a thin, purpose-built workload (no ips `WorkloadDsl`). Each tick, it
 * draws a Poisson count for each of the four flows and emits that many requests, stamped at the tick with
 * a uniform-random intra-tick arrival position; put / update items are sized from their uniform byte
 * ranges. The result is a time-ordered `Timed[DynamoDbRequest]` stream (the runner adds `Tick` /
 * `EndOfTime` via `TickFraming`).
 */
object OrderTrackingWorkload:

  /** Generate the full run's arrivals, in conceptual-time order, tagged with the scenario id. */
  def arrivals(config: OrderTrackingConfig, rng: UniformRandomProvider): Vector[Timed[DynamoDbRequest]] =
    val putRate    = PoissonSampler.constant(config.putRatePerTick)
    val getRate    = PoissonSampler.constant(config.getRatePerTick)
    val updateRate = PoissonSampler.constant(config.updateRatePerTick)
    val deleteRate = PoissonSampler.constant(config.deleteRatePerTick)
    val putBytes    = UniformSampler.constant(config.putItemBytes.minBytes.toDouble,    config.putItemBytes.maxBytes.toDouble)
    val updateBytes = UniformSampler.constant(config.updateItemBytes.minBytes.toDouble, config.updateItemBytes.maxBytes.toDouble)
    val baseQueryRate = PoissonSampler.constant(config.baseQueryRatePerTick)
    val baseScanRate  = PoissonSampler.constant(config.baseScanRatePerTick)
    val gsiQueryRate  = PoissonSampler.constant(config.gsiQueryRatePerTick)
    val gsiScanRate   = PoissonSampler.constant(config.gsiScanRatePerTick)

    def bytesFrom(sampler: UniformSampler, tick: Long): Long =
      math.max(1L, math.round(sampler.sample(tick, rng, ())._1))

    val out = Vector.newBuilder[Timed[DynamoDbRequest]]
    // Events must land in [1, simulationTicks] to match TickFraming's window contract (a tick-0 event
    // would be dropped by framing, and tick N+1 is the flush window).
    var tick = 1L
    while tick <= config.simulationTicks do
      val perTick = mutable.ArrayBuffer.empty[(Double, DynamoDbRequest)]

      def emit(count: Int, mk: () => DynamoDbRequest): Unit =
        var i = 0
        while i < count do
          val payload = mk()          // draws item bytes (put/update) before the position
          val phi     = rng.nextDouble()
          perTick += ((phi, payload))
          i += 1

      emit(putRate.sample(tick, rng, ())._1,    () => PutItemRequest(bytesFrom(putBytes, tick)))
      emit(getRate.sample(tick, rng, ())._1,    () => GetItemRequest)
      emit(updateRate.sample(tick, rng, ())._1, () => UpdateItemRequest(bytesFrom(updateBytes, tick)))
      emit(deleteRate.sample(tick, rng, ())._1, () => DeleteItemRequest)

      // Read flows: base reads at the table's consistency; GSI reads are always eventually consistent.
      // (For the non-indexed default all read rates are 0, and Poisson(0) draws no rng — so the phase-1
      // arrival stream is byte-identical.)
      emit(baseQueryRate.sample(tick, rng, ())._1, () => QueryRequest(DynamoDbTarget.Table, config.readConsistency))
      emit(baseScanRate.sample(tick, rng, ())._1,  () => ScanRequest(DynamoDbTarget.Table, config.readConsistency))
      config.globalSecondaryIndexes.foreach { gsi =>
        emit(gsiQueryRate.sample(tick, rng, ())._1, () => QueryRequest(gsi.target, ReadConsistency.EventuallyConsistent))
        emit(gsiScanRate.sample(tick, rng, ())._1,  () => ScanRequest(gsi.target, ReadConsistency.EventuallyConsistent))
      }

      perTick.sortInPlaceBy(_._1)
      perTick.foreach { case (phi, payload) => out += Timed(payload, SimTime.of(tick), phi, config.scenarioId) }
      tick += 1

    out.result()
