package stochastacy.examples.store

import org.apache.commons.rng.UniformRandomProvider
import stochastacy.core.component.Timed
import stochastacy.core.sampler.PoissonSampler
import stochastacy.sim.SimTime

/** One labeled arrival stream: a use-case name, a per-tick Poisson mean rate, and the request
 *  `template` minted at that rate. The `usecase` label rides each request's `Timed` envelope through
 *  the whole pipeline and becomes the first component of every statistics key — so two streams of the
 *  *same* shape but different labels (e.g. `list.keyset` vs `list.offset`) separate in the stats. */
final case class RequestStream(usecase: String, ratePerTick: Double, template: ApiRequest)

/** Client-level workload for the store pipeline: a **vector of labeled request streams**, each an
 *  independent per-tick Poisson process minting a fixed request template. Expressive enough to mix
 *  selectivity classes, pagination modes, and sustained writes in one run (the Slice 8 capstone)
 *  without a generic workload DSL — that stays a later-phase concern. */
final case class ApiWorkloadConfig(streams: Vector[RequestStream] = ApiWorkloadConfig.defaultStreams)

object ApiWorkloadConfig:
  import SelectivityClass.*
  import SortMode.*
  import Pagination.*

  /** A modest mixed workload — the general-purpose default used by the pipeline tests. */
  val defaultStreams: Vector[RequestStream] = Vector(
    RequestStream("get",    5.0, GetEntity()),
    RequestStream("create", 1.5, CreateEntity(1_024L)),
    RequestStream("update", 0.5, UpdateEntity(1_024L)),
    RequestStream("delete", 0.5, DeleteEntity()),
    RequestStream("list",   1.0, ListEntities(CategoryFilter, IndexOrdered, Keyset(20))),
    RequestStream("report", 0.2, GetReport(FullScan, 20, RequiresSort, Keyset(50)))
  )

  /** A single-use-case workload: only `get`s at the given per-tick mean rate. */
  def getOnly(rate: Double): ApiWorkloadConfig =
    ApiWorkloadConfig(Vector(RequestStream("get", rate, GetEntity())))

  /** The Slice 8 capstone workload: sustained creates (grows cardinality), point gets, category-filter
   *  lists under **both** keyset and deep-offset pagination (the deep-offset cliff), and full-scan
   *  reports (cardinality-driven cost rise). Offered load exceeds a modest admission cap, so it also
   *  throttles. Meant to be run against [[StoreConfig]] with a small `initialEntities` so the write-
   *  driven cardinality rise is visible over the run. */
  val capstoneStreams: Vector[RequestStream] = Vector(
    RequestStream("get",         8.0,  GetEntity()),
    RequestStream("create",      10.0, CreateEntity(1_024L)),                             // sustained writes
    RequestStream("list.keyset", 2.0,  ListEntities(CategoryFilter, IndexOrdered, Keyset(20))),     // flat page cost
    RequestStream("list.offset", 2.0,  ListEntities(CategoryFilter, IndexOrdered, Offset(10, 20))), // deep-offset cliff
    RequestStream("report",      1.0,  GetReport(FullScan, 20, RequiresSort, Keyset(50)))           // full-scan rise
  )

  def capstone: ApiWorkloadConfig = ApiWorkloadConfig(capstoneStreams)

object ApiWorkload:

  /** Eagerly draw a full run's worth of `ApiRequest`s, ordered by `eventTime` (tick ascending), each
   *  wrapped in a `Timed` envelope carrying its arrival time, `intraTick ~ U(0,1)`, and its stream's
   *  use-case label. Two RNG uses per request (the stream's Poisson count, then the arrival draw);
   *  fully deterministic given `rng`. */
  def requests(cfg: ApiWorkloadConfig, rng: UniformRandomProvider, simulationTicks: Long): Vector[Timed[ApiRequest]] =
    val samplers = cfg.streams.map(s => (s, PoissonSampler.constant(s.ratePerTick)))
    val b = Vector.newBuilder[Timed[ApiRequest]]
    (1L to simulationTicks).foreach { tick =>
      val t = SimTime.of(tick)
      samplers.foreach { case (s, sampler) =>
        val (n, _) = sampler.sample(tick, rng, ())
        var i = 0
        while i < n do
          b += Timed(s.template, t, rng.nextDouble(), s.usecase)
          i += 1
      }
    }
    b.result()
