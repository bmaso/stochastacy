package stochastacy.examples.store.v2

import scala.concurrent.{ExecutionContext, Future}

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, FanOutShape2, Graph}
import org.apache.pekko.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge, RunnableGraph, Sink}
import stochastacy.core.component.{ComponentResult, Interface, InterfaceSampler, ScheduleReleaseTransducer, Timed}
import stochastacy.core.stats.Statistics
import stochastacy.core.stream.TickFraming
import stochastacy.sim.{SimTime, TimedControlEvent, TimedElement, ticks}
import stochastacy.examples.store.*

/** Store Demo V2 edge: the store **datastore** behind a configurable stack of interface gates —
 *  `latency → rate-limiter → chaos → datastore` — driven by the store's own workload. Every gating
 *  behavior is a generic gate the interface component composes onto the datastore; rejections surface
 *  in-band (`ErrorResult("throttled")` = 429, `ErrorResult("unavailable")` = 503).
 *
 *  Two planes are folded into windowed statistics: the datastore's own consumption (latency, work),
 *  and — because gates emit no metric plane — the **terminal outcome of every request**, classified
 *  from the response stream into `outcome.served` / `outcome.throttled` / `outcome.chaos` (each 0/1, so
 *  its mean is a rate). All new code; the original store demo is untouched, reused by import.
 *
 *  Deterministic given `seed`. Requests span `[1, requestTicks]`, framed over `[1, simulationTicks]`;
 *  a `requestTicks < simulationTicks` tail lets every response drain (exact 1:1). */
object StoreV2TrialRunner:

  /** The datastore component's graph type — the shape every `Interface.wrap` preserves, so the folded
   *  gate stack has this same type. */
  private type EdgeGraph = Graph[
    FanOutShape2[TimedElement[Timed[StoreRequest]], TimedElement[Timed[StoreResponse]], TimedElement[Timed[Consumption]]],
    Future[ComponentResult[StoreState]]
  ]

  /** Structured entry point (the demo): build the gate stack from an [[EdgeConfig]]. */
  def run(
    apiCfg:          ApiWorkloadConfig,
    storeCfg:        StoreConfig,
    edge:            EdgeConfig,
    seed:            Long,
    simulationTicks: Long,
    requestTicks:    Long = -1L,
    windowTicks:     Long = Long.MaxValue
  )(using system: ActorSystem): Future[StoreV2TrialResult] =
    runGates(apiCfg, storeCfg, EdgeConfig.gates(edge), seed, simulationTicks, requestTicks, windowTicks)

  /** Raw entry point (experiments): wrap an explicit gate stack, outermost-first. */
  def runGates(
    apiCfg:          ApiWorkloadConfig,
    storeCfg:        StoreConfig,
    gates:           Seq[InterfaceSampler[?, StoreRequest, StoreResponse]],
    seed:            Long,
    simulationTicks: Long,
    requestTicks:    Long = -1L,
    windowTicks:     Long = Long.MaxValue
  )(using system: ActorSystem): Future[StoreV2TrialResult] =
    val reqTicks    = if requestTicks < 0L then simulationTicks else requestTicks
    val master      = RandomSource.KISS.create(seed)
    val workloadRng = RandomSource.KISS.create(master.nextLong())
    val storeRng    = RandomSource.KISS.create(master.nextLong())
    val gateRngs    = gates.map(_ => RandomSource.KISS.create(master.nextLong()))

    val storeReqs = ApiWorkload.requests(apiCfg, workloadRng, reqTicks)
      .map(t => Timed(toStoreRequest(t.event), t.eventTime, t.intraTick, t.usecase))
    val source = TickFraming.frameSource(storeReqs.iterator, simulationTicks)

    // Wrap the gates over the datastore, outermost-first (each wrap preserves shape + Mat).
    val datastore = ScheduleReleaseTransducer.componentOf(new StoreSampler(storeCfg), storeRng)
    val edge = gates.zip(gateRngs).foldRight(datastore: EdgeGraph) {
      case ((gate, rng), acc) => Interface.wrap(acc, gate, rng)
    }

    def windowOf(et: SimTime): Int =
      if windowTicks <= 0L then 0 else ((et.ticks - 1L) / windowTicks).toInt

    val datastoreObsFlow = Flow[TimedElement[Timed[Consumption]]].mapConcat {
      case t: Timed[Consumption] @unchecked =>
        val w = windowOf(t.eventTime)
        StoreStats.observations(t.event).map { case (m, v) => (StoreStatKey(t.usecase.toString, m, w), v) }
      case _: TimedControlEvent => Nil
    }
    val outcomeFlow = Flow[TimedElement[Timed[StoreResponse]]].mapConcat {
      case t: Timed[StoreResponse] @unchecked =>
        val w   = windowOf(t.eventTime)
        val hit = classify(t.event)
        OutcomeMetrics.map(m => (StoreStatKey(t.usecase.toString, m, w), if m == hit then 1.0 else 0.0))
      case _: TimedControlEvent => Nil
    }
    val payloadFlow = Flow[TimedElement[Timed[StoreResponse]]].collect { case t: Timed[StoreResponse] @unchecked => t.event }

    val statsSink = Sink.fold(Statistics.empty[StoreStatKey]) { (acc: Statistics[StoreStatKey], kv: (StoreStatKey, Double)) =>
      acc.observe(kv._1, kv._2)
    }
    val respSink = Sink.seq[StoreResponse]

    val graph = RunnableGraph.fromGraph(
      GraphDSL.createGraph(edge, statsSink, respSink)((e, s, r) => (e, s, r)) { implicit b => (edgeShape, statsShape, respShape) =>
        import GraphDSL.Implicits.*
        val src   = b.add(source)
        val bcast = b.add(Broadcast[TimedElement[Timed[StoreResponse]]](2))
        val merge = b.add(Merge[(StoreStatKey, Double)](2))

        src ~> edgeShape.in
        edgeShape.out0 ~> bcast.in
        bcast.out(0) ~> b.add(payloadFlow)   ~> respShape
        bcast.out(1) ~> b.add(outcomeFlow)   ~> merge.in(0)
        edgeShape.out1 ~> b.add(datastoreObsFlow) ~> merge.in(1)
        merge.out ~> statsShape
        ClosedShape
      }
    )

    given ExecutionContext = system.dispatcher
    val (edgeF, statsF, respF) = graph.run()
    for
      cr    <- edgeF
      stats <- statsF
      resp  <- respF
    yield StoreV2TrialResult(cr.finalState, simulationTicks, cr.residue, stats, resp.toVector)

  private val OutcomeMetrics = Seq("outcome.served", "outcome.throttled", "outcome.chaos")

  private def classify(r: StoreResponse): String = r match
    case ErrorResult("throttled")   => "outcome.throttled"
    case ErrorResult("unavailable") => "outcome.chaos"
    case _                          => "outcome.served"

  private def toStoreRequest(in: ApiRequest): StoreRequest = in match
    case GetEntity()                    => Get()
    case CreateEntity(sizeBytes)        => Put(sizeBytes)
    case UpdateEntity(sizeBytes)        => Put(sizeBytes)
    case DeleteEntity()                 => Delete()
    case ListEntities(sel, sort, page)  => ListQuery(sel, sort, page)
    case GetReport(sel, gc, sort, page) => ReportQuery(sel, gc, sort, page)
