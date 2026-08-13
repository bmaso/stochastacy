package stochastacy.examples.store

import org.apache.commons.rng.UniformRandomProvider
import stochastacy.core.component.Timed
import stochastacy.core.sampler.PoissonSampler
import stochastacy.sim.SimTime

/** Client-level workload for the store pipeline: per-tick Poisson arrival rates per `ApiRequest`
 *  kind, with fixed-ish per-request parameters. Drives `ingress → datastore → egress`. The generic
 *  `WorkloadDefinition` layer (Slice 8) is out of scope; this is just enough to drive the pipeline. */
final case class ApiWorkloadConfig(
  getPerTick:    Double = 5.0,
  createPerTick: Double = 1.5,
  updatePerTick: Double = 0.5,
  deletePerTick: Double = 0.5,
  listPerTick:   Double = 1.0,
  reportPerTick: Double = 0.2,
  entitySizeBytes: Long = 1_024L,
  listSel:       SelectivityClass = SelectivityClass.CategoryFilter,
  listSort:      SortMode         = SortMode.IndexOrdered,
  listPage:      Pagination       = Pagination.Keyset(20),
  reportSel:     SelectivityClass = SelectivityClass.FullScan,
  reportGroupCount: Int           = 20,
  reportSort:    SortMode         = SortMode.RequiresSort,
  reportPage:    Pagination       = Pagination.Keyset(50)
)

object ApiWorkload:

  /** Eagerly draw a full run's worth of `ApiRequest`s, ordered by `eventTime` (tick ascending), each
   *  wrapped in a `Timed` envelope carrying its arrival time, `intraTick ~ U(0,1)`, and use-case.
   *  Use-cases (`get`/`report`/…) propagate through every pipeline stage via the envelope and become
   *  the first component of each statistics key. Fully deterministic given `rng`. */
  def requests(cfg: ApiWorkloadConfig, rng: UniformRandomProvider, simulationTicks: Long): Vector[Timed[ApiRequest]] =
    val getRate    = PoissonSampler.constant(cfg.getPerTick)
    val createRate = PoissonSampler.constant(cfg.createPerTick)
    val updateRate = PoissonSampler.constant(cfg.updatePerTick)
    val deleteRate = PoissonSampler.constant(cfg.deletePerTick)
    val listRate   = PoissonSampler.constant(cfg.listPerTick)
    val reportRate = PoissonSampler.constant(cfg.reportPerTick)

    val b = Vector.newBuilder[Timed[ApiRequest]]
    (1L to simulationTicks).foreach { tick =>
      val t = SimTime.of(tick)
      def emit(req: ApiRequest, usecase: String): Unit = b += Timed(req, t, rng.nextDouble(), usecase)

      val (nGet, _) = getRate.sample(tick, rng, ())
      (0 until nGet).foreach(_ => emit(GetEntity(), "get"))

      val (nCreate, _) = createRate.sample(tick, rng, ())
      (0 until nCreate).foreach(_ => emit(CreateEntity(cfg.entitySizeBytes), "create"))

      val (nUpdate, _) = updateRate.sample(tick, rng, ())
      (0 until nUpdate).foreach(_ => emit(UpdateEntity(cfg.entitySizeBytes), "update"))

      val (nDelete, _) = deleteRate.sample(tick, rng, ())
      (0 until nDelete).foreach(_ => emit(DeleteEntity(), "delete"))

      val (nList, _) = listRate.sample(tick, rng, ())
      (0 until nList).foreach(_ => emit(ListEntities(cfg.listSel, cfg.listSort, cfg.listPage), "list"))

      val (nReport, _) = reportRate.sample(tick, rng, ())
      (0 until nReport).foreach(_ => emit(GetReport(cfg.reportSel, cfg.reportGroupCount, cfg.reportSort, cfg.reportPage), "report"))
    }
    b.result()
