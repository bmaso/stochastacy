package stochastacy.examples.store

import org.apache.commons.rng.simple.RandomSource
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.core.component.Scheduled

class IngressSamplerSpec extends AnyWordSpec with should.Matchers:

  private val cfg = ServiceConfig(ingressLatencyTicks = 0.05)
  private val ingress = new IngressSampler(cfg)
  private val rng = RandomSource.KISS.create(1L)

  private def emit(api: ApiRequest) = ingress.sample(api, (), rng)

  "IngressSampler" should {

    "translate each ApiRequest 1:1 to its downstream StoreRequest as the forward output" in {
      emit(GetEntity()).output.event shouldBe Get()
      emit(CreateEntity(2048L)).output.event shouldBe Put(2048L)
      emit(UpdateEntity(512L)).output.event shouldBe Put(512L)
      emit(DeleteEntity()).output.event shouldBe Delete()
      emit(ListEntities(SelectivityClass.CategoryFilter, SortMode.IndexOrdered, Pagination.Keyset(20)))
        .output.event shouldBe ListQuery(SelectivityClass.CategoryFilter, SortMode.IndexOrdered, Pagination.Keyset(20))
      emit(GetReport(SelectivityClass.FullScan, 20, SortMode.RequiresSort, Pagination.Keyset(50)))
        .output.event shouldBe ReportQuery(SelectivityClass.FullScan, 20, SortMode.RequiresSort, Pagination.Keyset(50))
    }

    "stamp the forward output at the ingress latency and emit a matching latency observation" in {
      val e = emit(GetEntity())
      e.output.delay shouldBe cfg.ingressLatencyTicks
      e.consumption shouldBe List(Scheduled(ServiceLatency(cfg.ingressLatencyTicks), cfg.ingressLatencyTicks))
    }

    "be stateless" in {
      emit(GetEntity()).newState shouldBe (())
    }
  }
