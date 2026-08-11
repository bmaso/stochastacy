package stochastacy.examples.store

import org.apache.commons.rng.UniformRandomProvider
import stochastacy.core.sampler.PoissonSampler
import stochastacy.sim.SimTime

/** Minimal workload description for the store simulator: per-tick Poisson arrival rates per request
 *  kind, with fixed-ish per-request parameters. The generic `WorkloadDefinition` layer (Slice 8) is
 *  out of scope; this is just enough to drive the datastore. */
final case class StoreWorkloadConfig(
  getPerTick:    Double = 5.0,
  putPerTick:    Double = 2.0,
  listPerTick:   Double = 1.0,
  reportPerTick: Double = 0.2,
  deletePerTick: Double = 0.5,
  putSizeBytes:  Long = 1_024L,
  listSel:       SelectivityClass = SelectivityClass.CategoryFilter,
  listSort:      SortMode         = SortMode.IndexOrdered,
  listPage:      Pagination       = Pagination.Keyset(20),
  reportSel:     SelectivityClass = SelectivityClass.FullScan,
  reportGroupCount: Int           = 20,
  reportSort:    SortMode         = SortMode.RequiresSort,
  reportPage:    Pagination       = Pagination.Keyset(50)
)

object StoreWorkload:

  /** Eagerly draw a full run's worth of requests, ordered by `eventTime` (tick ascending). Counts
   *  per kind per tick are Poisson; `intraTick ~ U(0,1)`. Fully deterministic given `rng`. */
  def requests(cfg: StoreWorkloadConfig, rng: UniformRandomProvider, simulationTicks: Long): Vector[StoreRequest] =
    val getRate    = PoissonSampler.constant(cfg.getPerTick)
    val putRate    = PoissonSampler.constant(cfg.putPerTick)
    val listRate   = PoissonSampler.constant(cfg.listPerTick)
    val reportRate = PoissonSampler.constant(cfg.reportPerTick)
    val deleteRate = PoissonSampler.constant(cfg.deletePerTick)

    val b = Vector.newBuilder[StoreRequest]
    (1L to simulationTicks).foreach { tick =>
      val t = SimTime.of(tick)
      def phi(): Double = rng.nextDouble()

      val (nGet, _) = getRate.sample(tick, rng, ())
      (0 until nGet).foreach(_ => b += Get(t, phi()))

      val (nPut, _) = putRate.sample(tick, rng, ())
      (0 until nPut).foreach(_ => b += Put(cfg.putSizeBytes, t, phi()))

      val (nList, _) = listRate.sample(tick, rng, ())
      (0 until nList).foreach(_ => b += ListQuery(cfg.listSel, cfg.listSort, cfg.listPage, t, phi()))

      val (nReport, _) = reportRate.sample(tick, rng, ())
      (0 until nReport).foreach(_ => b += ReportQuery(cfg.reportSel, cfg.reportGroupCount, cfg.reportSort, cfg.reportPage, t, phi()))

      val (nDelete, _) = deleteRate.sample(tick, rng, ())
      (0 until nDelete).foreach(_ => b += Delete(t, phi()))
    }
    b.result()
