package stochastacy.examples.store

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.simple.RandomSource
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.core.component.Emission

class StoreSamplerSpec extends AnyWordSpec with should.Matchers:

  private def rng(seed: Long = 42L): UniformRandomProvider = RandomSource.KISS.create(seed)

  private def queryOf(e: Emission[StoreState, StoreResponse, Consumption]): QueryResult =
    e.output.event match
      case q: QueryResult => q
      case other          => fail(s"expected QueryResult, got $other")

  "StoreSampler point operations" should {

    "return a hit with mean-sized bytes, no state change, and completion-timed consumption" in {
      val cfg = StoreConfig(hitRate = 1.0)
      val smp = new StoreSampler(cfg)
      val s   = StoreState(10L, 10L * 2048L) // meanBytes = 2048
      val e   = smp.sample(Get(), s, rng())

      e.output.event shouldBe GetResult(hit = true, bytes = 2048L)
      e.newState shouldBe s
      // D-B: consumption is accounted at completion — same delay as the response.
      e.output.delay shouldBe cfg.pointLatency
      e.consumption.forall(_.delay == cfg.pointLatency) shouldBe true
      e.consumption.map(_.event) should contain allOf (WorkPerformed(1L, 2048L), DataReturned(1L, 2048L))
    }

    "return a miss with zero bytes and zero returned items" in {
      val smp = new StoreSampler(StoreConfig(hitRate = 0.0))
      val e   = smp.sample(Get(), StoreState(10L, 10L * 2048L), rng())
      e.output.event shouldBe GetResult(hit = false, bytes = 0L)
      e.consumption.map(_.event) should contain(DataReturned(0L, 0L))
    }

    "grow cardinality and bytes on a creating Put, emitting a positive StorageDelta" in {
      val smp = new StoreSampler(StoreConfig(createRate = 1.0))
      val e   = smp.sample(Put(2000L), StoreState(10L, 10L * 1000L), rng()) // meanBytes = 1000
      e.output.event shouldBe WriteResult(created = true)
      e.newState shouldBe StoreState(11L, 10L * 1000L + 2000L)
      e.consumption.map(_.event) should contain(StorageDelta(2000L))
    }

    "keep cardinality but adjust bytes on an updating Put (delta = size - meanBytes)" in {
      val smp = new StoreSampler(StoreConfig(createRate = 0.0))
      val e   = smp.sample(Put(2000L), StoreState(10L, 10_000L), rng()) // meanBytes = 1000
      e.output.event shouldBe WriteResult(created = false)
      e.newState shouldBe StoreState(10L, 11_000L) // 10000 + (2000 - 1000)
      e.consumption.map(_.event) should contain(StorageDelta(1000L))
    }

    "shrink state on a hit Delete and emit a negative StorageDelta" in {
      val smp = new StoreSampler(StoreConfig(hitRate = 1.0))
      val e   = smp.sample(Delete(), StoreState(10L, 10_000L), rng()) // meanBytes = 1000
      e.output.event shouldBe DeleteResult(deleted = true)
      e.newState shouldBe StoreState(9L, 9_000L)
      e.consumption.map(_.event) should contain(StorageDelta(-1000L))
    }

    "leave state untouched on a miss Delete" in {
      val smp = new StoreSampler(StoreConfig(hitRate = 0.0))
      val s   = StoreState(10L, 10_000L)
      val e   = smp.sample(Delete(), s, rng())
      e.output.event shouldBe DeleteResult(deleted = false)
      e.newState shouldBe s
    }
  }

  "StoreSampler list queries" should {

    "climb evaluated cost with page depth under offset pagination (the deep-offset cliff)" in {
      val smp = new StoreSampler(StoreConfig())
      val big = StoreState(100_000L, 100_000L * 1024L)
      def evaluated(pageIndex: Int): Long =
        queryOf(
          smp.sample(
            ListQuery(SelectivityClass.FullScan, SortMode.IndexOrdered, Pagination.Offset(pageIndex, 10)),
            big,
            rng()
          )
        ).evaluatedItems

      evaluated(0) shouldBe 10L
      evaluated(5) shouldBe 60L
      evaluated(50) shouldBe 510L
      evaluated(0) should be < evaluated(5)
      evaluated(5) should be < evaluated(50)
    }

    "keep evaluated cost flat under keyset pagination regardless of matched size" in {
      val smp = new StoreSampler(StoreConfig())
      def evaluated(entityCount: Long): Long =
        queryOf(
          smp.sample(
            ListQuery(SelectivityClass.FullScan, SortMode.IndexOrdered, Pagination.Keyset(10)),
            StoreState(entityCount, entityCount * 1024L),
            rng()
          )
        ).evaluatedItems

      evaluated(1_000L) shouldBe 10L
      evaluated(1_000_000L) shouldBe 10L
    }

    "realize PointLookup as a ~constant count independent of entity count" in {
      val smp = new StoreSampler(StoreConfig(pointLookupMean = 3.0))
      def matched(entityCount: Long): Long =
        queryOf(
          smp.sample(
            // RequiresSort → evaluated == matched, so evaluatedItems reveals the realized match count.
            ListQuery(SelectivityClass.PointLookup, SortMode.RequiresSort, Pagination.Keyset(10)),
            StoreState(entityCount, entityCount * 1024L),
            rng(seed = 7L) // fresh, identical seed → identical Poisson draw
          )
        ).evaluatedItems

      matched(1_000L) shouldBe matched(1_000_000L) // does NOT scale with N
      matched(1_000L) should be < 50L
    }

    "realize CategoryFilter as a ~constant fraction that scales with entity count" in {
      val smp = new StoreSampler(StoreConfig(categoryFraction = 0.40))
      def matched(entityCount: Long): Long =
        queryOf(
          smp.sample(
            ListQuery(SelectivityClass.CategoryFilter, SortMode.RequiresSort, Pagination.Keyset(10)),
            StoreState(entityCount, entityCount * 1024L),
            rng()
          )
        ).evaluatedItems

      matched(1_000L) shouldBe 400L
      matched(1_000_000L) shouldBe 400_000L
    }
  }

  "StoreSampler report queries" should {

    "evaluate the whole matched set but return only the (paged) group count" in {
      val smp = new StoreSampler(StoreConfig())
      val e = smp.sample(
        ReportQuery(SelectivityClass.FullScan, groupCount = 20, SortMode.RequiresSort, Pagination.Keyset(50)),
        StoreState(100_000L, 100_000L * 1024L),
        rng()
      )
      val q = queryOf(e)
      q.evaluatedItems shouldBe 100_000L      // aggregation sees everything
      q.returnedItems shouldBe 20L            // min(pageSize 50, groupCount 20)
      q.returnedBytes shouldBe 20L * 256L     // meanGroupBytes
    }
  }

  "StoreSampler" should {

    "take the error branch, emitting an ErrorResult with no consumption and no state change" in {
      val smp = new StoreSampler(StoreConfig(errorRate = 1.0))
      val s   = StoreState(100L, 100L * 1024L)
      val e   = smp.sample(Get(), s, rng())
      e.output.event shouldBe ErrorResult("system")
      e.consumption shouldBe empty
      e.newState shouldBe s
    }

    "grow entity count by one per creating Put when state is threaded across many requests" in {
      val smp = new StoreSampler(StoreConfig(createRate = 1.0))
      val r   = rng()
      var st  = smp.initialState
      (1 to 1000).foreach { i =>
        st = smp.sample(Put(500L), st, r).newState
      }
      st.entityCount shouldBe smp.initialState.entityCount + 1000L
    }
  }
