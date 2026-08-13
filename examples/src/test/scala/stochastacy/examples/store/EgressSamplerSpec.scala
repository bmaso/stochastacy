package stochastacy.examples.store

import org.apache.commons.rng.simple.RandomSource
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.core.component.Scheduled

class EgressSamplerSpec extends AnyWordSpec with should.Matchers:

  private val cfg = ServiceConfig(egressLatencyTicks = 0.05)
  private val egress = new EgressSampler(cfg)
  private val rng = RandomSource.KISS.create(1L)

  private def emit(resp: StoreResponse) = egress.sample(resp, (), rng)

  "EgressSampler" should {

    "map each StoreResponse 1:1 to its client-facing ApiResponse as the forward output" in {
      emit(GetResult(hit = true, bytes = 2048L)).output.event shouldBe EntityResult(found = true, bytes = 2048L)
      emit(GetResult(hit = false, bytes = 0L)).output.event shouldBe EntityResult(found = false, bytes = 0L)
      emit(WriteResult(created = true)).output.event shouldBe EntityWritten(created = true)
      emit(DeleteResult(deleted = false)).output.event shouldBe EntityDeleted(deleted = false)
      emit(ErrorResult("system")).output.event shouldBe ApiError("system")
    }

    "drop a QueryResult's internal evaluated* counts, exposing only what was returned" in {
      emit(QueryResult(returnedItems = 20L, returnedBytes = 5120L, evaluatedItems = 100_000L, evaluatedBytes = 1L))
        .output.event shouldBe QueryResponse(returnedItems = 20L, returnedBytes = 5120L)
    }

    "stamp the forward output at the egress latency and emit a matching latency observation" in {
      val e = emit(WriteResult(created = true))
      e.output.delay shouldBe cfg.egressLatencyTicks
      e.consumption shouldBe List(Scheduled(ServiceLatency(cfg.egressLatencyTicks), cfg.egressLatencyTicks))
    }

    "be stateless" in {
      emit(WriteResult(created = true)).newState shouldBe (())
    }
  }
