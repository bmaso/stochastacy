package stochastacy.examples.store

import scala.concurrent.{ExecutionContext, Future}

import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.ClosedShape
import org.apache.pekko.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge, RunnableGraph, Sink}
import stochastacy.core.component.{ScheduleReleaseTransducer, Timed}
import stochastacy.core.stats.Statistics
import stochastacy.core.stream.TickFraming
import stochastacy.sim.stream.MergeTimedEventGraph
import stochastacy.sim.{TimedControlEvent, TimedElement, TimedEvent}

/** Runs one trial of the full store pipeline — `api-workload → ingress → admission → datastore →
 *  egress` — and folds every stage's consumption into per-(use-case, metric) statistics, keeping the
 *  client responses for integrity checks. The problem-specific runner the store owns, built on the
 *  generic transducer + `core.stats` base types; the materialized-value combination is wired inline
 *  (no `core` combiner yet — not enough examples to know its shape).
 *
 *  Admission (Slice 6b) forks the pipeline: its forward output is admitted-or-throttled; admitted
 *  requests continue to the datastore, throttled ones become an `ErrorResult("throttled")` that is
 *  tick-aligned-merged back with the datastore's responses before egress (via `MergeTimedEventGraph`,
 *  which keeps one `Tick` per window and a single terminal `EndOfTime`). Egress maps that error to a
 *  client `ApiError("throttled")`, so throttling preserves 1:1 request/response integrity with no new
 *  response type.
 *
 *  A master seed is split into independent per-stage RNGs (ingress/admission/egress are deterministic);
 *  the whole trial is deterministic given `seed`. Requests are generated over `[1, requestTicks]` but
 *  framed over `[1, simulationTicks]`; leaving `requestTicks < simulationTicks` pads the tail with
 *  empty ticks so every response drains within the horizon (an exact-1:1 aid — D-3). */
object StoreTrialRunner:

  def run(
    apiCfg:          ApiWorkloadConfig,
    storeCfg:        StoreConfig,
    serviceCfg:      ServiceConfig,
    seed:            Long,
    simulationTicks: Long,
    admissionCfg:    AdmissionConfig = AdmissionConfig(),
    requestTicks:    Long            = -1L
  )(using system: ActorSystem): Future[StoreTrialResult] =
    val reqTicks    = if requestTicks < 0L then simulationTicks else requestTicks
    val master      = RandomSource.KISS.create(seed)
    val workloadRng = RandomSource.KISS.create(master.nextLong())
    val ingressRng  = RandomSource.KISS.create(master.nextLong())
    val admissionRng = RandomSource.KISS.create(master.nextLong())
    val storeRng    = RandomSource.KISS.create(master.nextLong())
    val egressRng   = RandomSource.KISS.create(master.nextLong())

    val requests = ApiWorkload.requests(apiCfg, workloadRng, reqTicks)
    val source   = TickFraming.frameSource(requests.iterator, simulationTicks)

    val ingress   = ScheduleReleaseTransducer.componentOf(new IngressSampler(serviceCfg), ingressRng)
    val admission = ScheduleReleaseTransducer.componentOf(new AdmissionSampler(admissionCfg), admissionRng)
    val datastore = ScheduleReleaseTransducer.componentOf(new StoreSampler(storeCfg), storeRng)
    val egress    = ScheduleReleaseTransducer.componentOf(new EgressSampler(serviceCfg), egressRng)

    // --- fork/rejoin adapters (Slice 6b) ---
    // Admitted branch: keep admitted payloads (unwrapping the request), pass every control event.
    val admittedFlow: Flow[TimedElement[Timed[AdmissionOutcome]], TimedElement[Timed[StoreRequest]], NotUsed] =
      Flow[TimedElement[Timed[AdmissionOutcome]]].collect {
        case t: Timed[AdmissionOutcome] @unchecked if t.event.isInstanceOf[Admitted] =>
          val req = t.event.asInstanceOf[Admitted].request
          (Timed(req, t.eventTime, t.intraTick, t.usecase): TimedElement[Timed[StoreRequest]])
        case c: TimedControlEvent => c
      }
    // Throttled branch: turn each throttle into a store-level error response, pass every control event.
    val throttledFlow: Flow[TimedElement[Timed[AdmissionOutcome]], TimedElement[Timed[StoreResponse]], NotUsed] =
      Flow[TimedElement[Timed[AdmissionOutcome]]].collect {
        case t: Timed[AdmissionOutcome] @unchecked if t.event == Throttled =>
          (Timed(ErrorResult("throttled"), t.eventTime, t.intraTick, t.usecase): TimedElement[Timed[StoreResponse]])
        case c: TimedControlEvent => c
      }
    // Merge output is the erased `TimedEvent`; recover the concrete response element type for egress.
    val mergedBackFlow: Flow[TimedEvent, TimedElement[Timed[StoreResponse]], NotUsed] =
      Flow[TimedEvent].collect {
        case t: Timed[StoreResponse] @unchecked => (t: TimedElement[Timed[StoreResponse]])
        case c: TimedControlEvent               => c
      }

    // Per-stage translators: each component's consumption stream → common (StoreStatKey, Double).
    def serviceLatFlow(metric: String): Flow[TimedElement[Timed[ServiceConsumption]], (StoreStatKey, Double), NotUsed] =
      Flow[TimedElement[Timed[ServiceConsumption]]].mapConcat {
        case t: Timed[ServiceConsumption] @unchecked =>
          t.event match { case ServiceLatency(v) => List((StoreStatKey(t.usecase.toString, metric), v)) }
        case _: TimedControlEvent => Nil
      }
    val admissionObsFlow: Flow[TimedElement[Timed[AdmissionConsumption]], (StoreStatKey, Double), NotUsed] =
      Flow[TimedElement[Timed[AdmissionConsumption]]].mapConcat {
        case t: Timed[AdmissionConsumption] @unchecked =>
          StoreStats.admissionObservations(t.event).map { case (m, v) => (StoreStatKey(t.usecase.toString, m), v) }
        case _: TimedControlEvent => Nil
      }
    val storeObsFlow: Flow[TimedElement[Timed[Consumption]], (StoreStatKey, Double), NotUsed] =
      Flow[TimedElement[Timed[Consumption]]].mapConcat {
        case t: Timed[Consumption] @unchecked =>
          StoreStats.observations(t.event).map { case (metric, v) => (StoreStatKey(t.usecase.toString, metric), v) }
        case _: TimedControlEvent => Nil
      }
    val payloadFlow: Flow[TimedElement[Timed[ApiResponse]], ApiResponse, NotUsed] =
      Flow[TimedElement[Timed[ApiResponse]]].collect { case t: Timed[ApiResponse] @unchecked => t.event }

    val statsSink = Sink.fold(Statistics.empty[StoreStatKey]) { (acc: Statistics[StoreStatKey], kv: (StoreStatKey, Double)) =>
      acc.observe(kv._1, kv._2)
    }
    val respSink = Sink.seq[ApiResponse]

    val graph = RunnableGraph.fromGraph(
      GraphDSL.createGraph(datastore, statsSink, respSink)((ds, stats, resp) => (ds, stats, resp)) {
        implicit b => (dsShape, statsShape, respShape) =>
          import GraphDSL.Implicits.*
          val src    = b.add(source)
          val ing    = b.add(ingress)
          val adm    = b.add(admission)
          val egr    = b.add(egress)
          val bcast  = b.add(Broadcast[TimedElement[Timed[AdmissionOutcome]]](2))
          val rejoin = b.add(MergeTimedEventGraph.graphOf())
          val merge  = b.add(Merge[(StoreStatKey, Double)](4))

          // forward chain: source → ingress → admission → {admitted → datastore, throttled} → merge → egress → responses
          src ~> ing.in
          ing.out0 ~> adm.in
          adm.out0 ~> bcast.in
          bcast.out(0) ~> b.add(admittedFlow)  ~> dsShape.in
          bcast.out(1) ~> b.add(throttledFlow) ~> rejoin.in1
          dsShape.out0 ~> rejoin.in0
          rejoin.out ~> b.add(mergedBackFlow) ~> egr.in
          egr.out0 ~> b.add(payloadFlow) ~> respShape

          // observation planes → common currency → merged fold
          ing.out1     ~> b.add(serviceLatFlow("ingress.latency")) ~> merge.in(0)
          adm.out1     ~> b.add(admissionObsFlow)                  ~> merge.in(1)
          dsShape.out1 ~> b.add(storeObsFlow)                      ~> merge.in(2)
          egr.out1     ~> b.add(serviceLatFlow("egress.latency"))  ~> merge.in(3)
          merge.out ~> statsShape

          ClosedShape
      }
    )

    given ExecutionContext = system.dispatcher
    val (dsResultF, statsF, respF) = graph.run()
    for
      ds    <- dsResultF
      stats <- statsF
      resp  <- respF
    yield StoreTrialResult(ds.finalState, simulationTicks, ds.residue, stats, resp.toVector)
