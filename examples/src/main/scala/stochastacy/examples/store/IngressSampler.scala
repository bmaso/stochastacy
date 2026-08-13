package stochastacy.examples.store

import org.apache.commons.rng.UniformRandomProvider
import stochastacy.core.component.{ComponentSampler, Emission, Scheduled}

/** The service ingress: translates each client `ApiRequest` into the downstream `StoreRequest` it
 *  issues (its forward **output**), adds ingress service latency, and emits a latency observation.
 *  Stateless — a 1:1 pass-through. This is the first component whose forward output is a *request*.
 *
 *  Use-case is not set here: the transducer propagates it from the `ApiRequest`'s `Timed` envelope
 *  onto the emitted `StoreRequest`'s envelope. */
final class IngressSampler(cfg: ServiceConfig)
    extends ComponentSampler[Unit, ApiRequest, StoreRequest, ServiceConsumption]:

  def initialState: Unit = ()

  def sample(in: ApiRequest, state: Unit, rng: UniformRandomProvider): Emission[Unit, StoreRequest, ServiceConsumption] =
    val lat = cfg.ingressLatencyTicks
    Emission((), Scheduled(toStoreRequest(in), lat), List(Scheduled(ServiceLatency(lat), lat)))

  private def toStoreRequest(in: ApiRequest): StoreRequest = in match
    case GetEntity()                    => Get()
    case CreateEntity(sizeBytes)        => Put(sizeBytes)
    case UpdateEntity(sizeBytes)        => Put(sizeBytes)
    case DeleteEntity()                 => Delete()
    case ListEntities(sel, sort, page)  => ListQuery(sel, sort, page)
    case GetReport(sel, gc, sort, page) => ReportQuery(sel, gc, sort, page)
