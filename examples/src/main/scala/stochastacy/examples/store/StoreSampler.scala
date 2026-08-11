package stochastacy.examples.store

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.statistics.distribution.PoissonDistribution
import stochastacy.core.component.{Delay, Emission, RequestResponseSampler, Scheduled}

/** The datastore behavior for the store simulator, as a `RequestResponseSampler` over the bounded
 *  summary [[StoreState]]. For each request it produces exactly one response (a success- or
 *  error-variant of [[StoreResponse]]) plus zero-or-more [[Consumption]] facts, and the updated
 *  state. It is stateless *per request* — load-dependent throttling is a separate admission
 *  component (Slice 6), not this sampler.
 *
 *  Consumption is accounted at completion (`delay = latency`), matching the response's timing, so
 *  windowed accounting attributes work to when the op finished. State mutates immediately in
 *  request order — that is the logical summary; consumption timing is the observable fact. */
final class StoreSampler(cfg: StoreConfig)
    extends RequestResponseSampler[StoreState, StoreRequest, StoreResponse, Consumption]:

  def initialState: StoreState =
    StoreState(cfg.initialEntities, cfg.initialEntities * cfg.meanEntityBytes)

  def sample(
    req:   StoreRequest,
    state: StoreState,
    rng:   UniformRandomProvider
  ): Emission[StoreState, StoreResponse, Consumption] =
    // Error branch first: a small stochastic system-error rate exercises the "either error" path.
    if bernoulli(rng, cfg.errorRate) then
      Emission(state, Scheduled(ErrorResult("system"), cfg.errorLatency), Nil)
    else
      req match
        case _: Get         => get(state, rng)
        case p: Put         => put(p, state, rng)
        case _: Delete      => delete(state, rng)
        case q: ListQuery   => list(q, state, rng)
        case r: ReportQuery => report(r, state, rng)

  // --- point operations ---

  private def get(s: StoreState, rng: UniformRandomProvider): Emission[StoreState, StoreResponse, Consumption] =
    val hit   = bernoulli(rng, cfg.hitRate)
    val bytes = if hit then s.meanBytes else 0L
    val lat   = cfg.pointLatency
    Emission(
      s, // reads do not mutate state
      Scheduled(GetResult(hit, bytes), lat),
      List(
        Scheduled(WorkPerformed(1L, bytes), lat),
        Scheduled(DataReturned(if hit then 1L else 0L, bytes), lat)
      )
    )

  private def put(p: Put, s: StoreState, rng: UniformRandomProvider): Emission[StoreState, StoreResponse, Consumption] =
    val created = bernoulli(rng, cfg.createRate)
    val delta   = if created then p.sizeBytes else p.sizeBytes - s.meanBytes // upsert: update replaces an avg item
    val next =
      if created then StoreState(s.entityCount + 1L, s.totalBytes + p.sizeBytes)
      else StoreState(s.entityCount, math.max(0L, s.totalBytes + delta))
    val lat = cfg.writeLatency
    Emission(
      next,
      Scheduled(WriteResult(created), lat),
      List(Scheduled(StorageDelta(delta), lat), Scheduled(WorkPerformed(1L, p.sizeBytes), lat))
    )

  private def delete(s: StoreState, rng: UniformRandomProvider): Emission[StoreState, StoreResponse, Consumption] =
    val deleted = bernoulli(rng, cfg.hitRate)
    val next =
      if deleted then StoreState(math.max(0L, s.entityCount - 1L), math.max(0L, s.totalBytes - s.meanBytes))
      else s
    val lat = cfg.writeLatency
    val cons =
      if deleted then List(Scheduled(StorageDelta(-s.meanBytes), lat), Scheduled(WorkPerformed(1L, s.meanBytes), lat))
      else List(Scheduled(WorkPerformed(1L, 0L), lat))
    Emission(next, Scheduled(DeleteResult(deleted), lat), cons)

  // --- list (ordered retrieval) ---

  private def list(q: ListQuery, s: StoreState, rng: UniformRandomProvider): Emission[StoreState, StoreResponse, Consumption] =
    val matched   = realizeMatched(q.sel, s, rng)
    val evaluated = evaluatedForList(matched, q.sort, q.page)
    val returned  = returnedForPage(matched, q.page)
    val evalBytes = evaluated * s.meanBytes
    val retBytes  = returned * s.meanBytes
    val lat       = cfg.queryBaseLatency + cfg.latencyPerEvaluatedItem * evaluated + sortPenalty(q.sort, matched)
    Emission(
      s,
      Scheduled(QueryResult(returned, retBytes, evaluated, evalBytes), lat),
      List(Scheduled(WorkPerformed(evaluated, evalBytes), lat), Scheduled(DataReturned(returned, retBytes), lat))
    )

  // --- report (aggregation) ---

  private def report(r: ReportQuery, s: StoreState, rng: UniformRandomProvider): Emission[StoreState, StoreResponse, Consumption] =
    val matched   = realizeMatched(r.sel, s, rng)
    val evaluated = matched // aggregation must see the whole matched set
    val returned  = returnedForPage(r.groupCount.toLong, r.page)
    val evalBytes = evaluated * s.meanBytes
    val retBytes  = returned * cfg.meanGroupBytes
    val lat       = cfg.reportBaseLatency + cfg.latencyPerEvaluatedItem * evaluated + sortPenalty(r.sort, r.groupCount.toLong)
    Emission(
      s,
      Scheduled(QueryResult(returned, retBytes, evaluated, evalBytes), lat),
      List(Scheduled(WorkPerformed(evaluated, evalBytes), lat), Scheduled(DataReturned(returned, retBytes), lat))
    )

  // --- cost-model helpers ---

  /** Realize a selectivity class against current state: constant-count, constant-fraction, or all. */
  private def realizeMatched(sel: SelectivityClass, s: StoreState, rng: UniformRandomProvider): Long =
    sel match
      case SelectivityClass.PointLookup    => poisson(rng, cfg.pointLookupMean)
      case SelectivityClass.CategoryFilter => math.floor(cfg.categoryFraction * s.entityCount).toLong
      case SelectivityClass.FullScan       => s.entityCount

  /** Items the store must evaluate — where the deep-offset cliff lives. */
  private def evaluatedForList(matched: Long, sort: SortMode, page: Pagination): Long =
    sort match
      case SortMode.IndexOrdered =>
        page match
          case Pagination.Keyset(ps)     => math.min(matched, ps.toLong)              // flat: cost of one page
          case Pagination.Offset(pi, ps) => math.min(matched, (pi.toLong + 1L) * ps.toLong) // deep-offset cliff
      case SortMode.RequiresSort | SortMode.Unordered =>
        matched // must scan all matches (RequiresSort additionally pays a sort penalty in latency)

  /** Items actually returned for a page over `total` matched (works for list items or report groups). */
  private def returnedForPage(total: Long, page: Pagination): Long =
    page match
      case Pagination.Keyset(ps)     => math.min(total, ps.toLong)
      case Pagination.Offset(pi, ps) => math.max(0L, math.min(ps.toLong, total - pi.toLong * ps.toLong))

  private def sortPenalty(sort: SortMode, items: Long): Delay =
    sort match
      case SortMode.RequiresSort => cfg.sortPenaltyPerItem * items
      case _                     => 0.0

  private def bernoulli(rng: UniformRandomProvider, p: Double): Boolean =
    rng.nextDouble() < math.min(1.0, math.max(0.0, p))

  private def poisson(rng: UniformRandomProvider, mean: Double): Long =
    if mean <= 0.0 then 0L else PoissonDistribution.of(mean).createSampler(rng).sample().toLong
