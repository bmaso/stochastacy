package stochastacy.examples.store

import scala.concurrent.{ExecutionContext, Future}

import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.ClosedShape
import org.apache.pekko.stream.scaladsl.{Flow, GraphDSL, Merge, RunnableGraph, Sink}
import stochastacy.core.component.{ScheduleReleaseTransducer, Timed}
import stochastacy.core.stats.Statistics
import stochastacy.core.stream.TickFraming
import stochastacy.sim.{TimedControlEvent, TimedElement}

/** Runs one trial of the full store pipeline — `api-workload → ingress → datastore → egress` — and
 *  folds every stage's consumption into per-(use-case, metric) statistics, keeping the client
 *  responses for integrity checks. The problem-specific runner the store owns, built on the generic
 *  transducer + `core.stats` base types; the materialized-value combination is wired inline (no
 *  `core` combiner yet — not enough examples to know its shape).
 *
 *  A master seed is split into independent workload/datastore RNGs (ingress/egress are deterministic);
 *  the whole trial is deterministic given `seed`. */
object StoreTrialRunner:

  def run(
    apiCfg:          ApiWorkloadConfig,
    storeCfg:        StoreConfig,
    serviceCfg:      ServiceConfig,
    seed:            Long,
    simulationTicks: Long
  )(using system: ActorSystem): Future[StoreTrialResult] =
    val master      = RandomSource.KISS.create(seed)
    val workloadRng = RandomSource.KISS.create(master.nextLong())
    val ingressRng  = RandomSource.KISS.create(master.nextLong())
    val storeRng    = RandomSource.KISS.create(master.nextLong())
    val egressRng   = RandomSource.KISS.create(master.nextLong())

    val requests = ApiWorkload.requests(apiCfg, workloadRng, simulationTicks)
    val source   = TickFraming.frameSource(requests.iterator, simulationTicks)

    val ingress   = ScheduleReleaseTransducer.componentOf(new IngressSampler(serviceCfg), ingressRng)
    val datastore = ScheduleReleaseTransducer.componentOf(new StoreSampler(storeCfg), storeRng)
    val egress    = ScheduleReleaseTransducer.componentOf(new EgressSampler(serviceCfg), egressRng)

    // Per-stage translators: each component's consumption stream → common (StoreStatKey, Double).
    def serviceLatFlow(metric: String): Flow[TimedElement[Timed[ServiceConsumption]], (StoreStatKey, Double), NotUsed] =
      Flow[TimedElement[Timed[ServiceConsumption]]].mapConcat {
        case t: Timed[ServiceConsumption] @unchecked =>
          t.event match { case ServiceLatency(v) => List((StoreStatKey(t.usecase.toString, metric), v)) }
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
          val src   = b.add(source)
          val ing   = b.add(ingress)
          val egr   = b.add(egress)
          val merge = b.add(Merge[(StoreStatKey, Double)](3))

          // forward chain: source → ingress → datastore → egress → responses
          src ~> ing.in
          ing.out0 ~> dsShape.in
          dsShape.out0 ~> egr.in
          egr.out0 ~> b.add(payloadFlow) ~> respShape

          // observation planes → common currency → merged fold
          ing.out1     ~> b.add(serviceLatFlow("ingress.latency")) ~> merge.in(0)
          dsShape.out1 ~> b.add(storeObsFlow)                      ~> merge.in(1)
          egr.out1     ~> b.add(serviceLatFlow("egress.latency"))  ~> merge.in(2)
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
