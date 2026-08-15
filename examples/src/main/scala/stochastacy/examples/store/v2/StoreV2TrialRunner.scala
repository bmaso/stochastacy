package stochastacy.examples.store.v2

import scala.concurrent.{ExecutionContext, Future}

import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.ClosedShape
import org.apache.pekko.stream.scaladsl.{Flow, GraphDSL, RunnableGraph, Sink}
import stochastacy.core.component.gate.FlatThrottleGate
import stochastacy.core.component.{Interface, ScheduleReleaseTransducer, Timed}
import stochastacy.core.stream.TickFraming
import stochastacy.sim.TimedElement
import stochastacy.examples.store.*

/** Store Demo V2, minimal edge (Slice 1): the store **datastore** wrapped by a `FlatThrottleGate`
 *  interface, driven by the store's own workload. Throttling is no longer a bespoke pipeline stage —
 *  it is a generic gate the interface component composes onto the datastore, and a throttled request
 *  surfaces in-band as `ErrorResult("throttled")` (later mapped to a client 429). All new code: the
 *  original store demo is untouched; this reuses its datastore, protocol, and workload by import.
 *
 *  Deterministic given `seed`. Requests are generated over `[1, requestTicks]` and framed over
 *  `[1, simulationTicks]`; a `requestTicks < simulationTicks` tail lets every response drain (exact
 *  1:1). */
object StoreV2TrialRunner:

  def run(
    apiCfg:           ApiWorkloadConfig,
    storeCfg:         StoreConfig,
    throttleCapacity: Int,
    seed:             Long,
    simulationTicks:  Long,
    requestTicks:     Long = -1L
  )(using system: ActorSystem): Future[StoreV2TrialResult] =
    val reqTicks    = if requestTicks < 0L then simulationTicks else requestTicks
    val master      = RandomSource.KISS.create(seed)
    val workloadRng = RandomSource.KISS.create(master.nextLong())
    val gateRng     = RandomSource.KISS.create(master.nextLong())
    val storeRng    = RandomSource.KISS.create(master.nextLong())

    val storeReqs = ApiWorkload.requests(apiCfg, workloadRng, reqTicks)
      .map(t => Timed(toStoreRequest(t.event), t.eventTime, t.intraTick, t.usecase))
    val source = TickFraming.frameSource(storeReqs.iterator, simulationTicks)

    val datastore = ScheduleReleaseTransducer.componentOf(new StoreSampler(storeCfg), storeRng)
    val gate      = new FlatThrottleGate[StoreRequest, StoreResponse](throttleCapacity, ErrorResult("throttled"))
    val edge      = Interface.wrap(datastore, gate, gateRng)

    val payloadFlow = Flow[TimedElement[Timed[StoreResponse]]].collect { case t: Timed[StoreResponse] @unchecked => t.event }
    val respSink    = Sink.seq[StoreResponse]

    val graph = RunnableGraph.fromGraph(
      GraphDSL.createGraph(edge, respSink)((e, r) => (e, r)) { implicit b => (edgeShape, respShape) =>
        import GraphDSL.Implicits.*
        val src = b.add(source)
        src ~> edgeShape.in
        edgeShape.out0 ~> b.add(payloadFlow) ~> respShape
        edgeShape.out1 ~> b.add(Sink.ignore)          // datastore consumption — unused in the minimal edge
        ClosedShape
      }
    )

    given ExecutionContext = system.dispatcher
    val (edgeResultF, respF) = graph.run()
    for
      cr   <- edgeResultF
      resp <- respF
    yield StoreV2TrialResult(cr.finalState, simulationTicks, cr.residue, resp.toVector)

  private def toStoreRequest(in: ApiRequest): StoreRequest = in match
    case GetEntity()                    => Get()
    case CreateEntity(sizeBytes)        => Put(sizeBytes)
    case UpdateEntity(sizeBytes)        => Put(sizeBytes)
    case DeleteEntity()                 => Delete()
    case ListEntities(sel, sort, page)  => ListQuery(sel, sort, page)
    case GetReport(sel, gc, sort, page) => ReportQuery(sel, gc, sort, page)
