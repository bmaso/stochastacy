package stochastacy.examples.store

import org.apache.commons.rng.UniformRandomProvider
import stochastacy.core.component.{ComponentSampler, Emission, Scheduled}

/** The service egress: maps each datastore `StoreResponse` into the client-facing `ApiResponse` (its
 *  forward **output**), adds egress service latency, and emits a latency observation. Stateless —
 *  the mirror image of [[IngressSampler]], the component whose *input* is a response.
 *
 *  The `ApiResponse` is a thinner client view: a `QueryResult`'s `evaluated*` counts (internal work)
 *  are dropped; the client sees only what was returned. */
final class EgressSampler(cfg: ServiceConfig)
    extends ComponentSampler[Unit, StoreResponse, ApiResponse, ServiceConsumption]:

  def initialState: Unit = ()

  def sample(in: StoreResponse, state: Unit, rng: UniformRandomProvider): Emission[Unit, ApiResponse, ServiceConsumption] =
    val lat = cfg.egressLatencyTicks
    Emission((), Scheduled(toApiResponse(in), lat), List(Scheduled(ServiceLatency(lat), lat)))

  private def toApiResponse(in: StoreResponse): ApiResponse = in match
    case GetResult(hit, bytes)          => EntityResult(hit, bytes)
    case WriteResult(created)           => EntityWritten(created)
    case DeleteResult(deleted)          => EntityDeleted(deleted)
    case QueryResult(ret, retBytes, _, _) => QueryResponse(ret, retBytes) // drop evaluated* (internal work)
    case ErrorResult(kind)              => ApiError(kind)
