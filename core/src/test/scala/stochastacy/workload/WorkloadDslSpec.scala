package stochastacy.workload

import org.apache.commons.rng.simple.RandomSource
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.DynamoDbReadTarget
import stochastacy.aws.dynamodb.table.ReadConsistency

class WorkloadDslSpec extends AnyWordSpec with should.Matchers:

  private val rng = RandomSource.KISS.create(42L)

  private def sampleInt(s: StatelessSampler[Int], tick: Long = 0L): Int =
    s.sample(tick, rng, ())._1

  private def sampleLong(s: StatelessSampler[Long], tick: Long = 0L): Long =
    s.sample(tick, rng, ())._1

  // ── File structure ──────────────────────────────────────────────────────────

  "WorkloadDsl file structure" should {

    "parse a minimal workload file with one empty workload" in {
      val yaml = """
        workloads:
          empty:
        """
      val file = WorkloadDsl.parse(yaml)
      val template = file.resolve("empty")
      template.flows           shouldBe empty
      template.requiredBindings shouldBe empty
    }

    "parse a file with multiple named workloads" in {
      val yaml = """
        workloads:
          a:
            flows:
              - type: get-item
                rate: 1
          b:
            flows:
              - type: delete-item
                rate: 2
        """
      val file = WorkloadDsl.parse(yaml)
      file.resolve("a").flows should have size 1
      file.resolve("b").flows should have size 1
    }

    "throw WorkloadDslException when 'workloads' key is absent" in {
      val yaml = "something: else"
      an[WorkloadDslException] should be thrownBy WorkloadDsl.parse(yaml)
    }

    "throw WorkloadDslException when resolving an unknown workload name" in {
      val yaml = """
        workloads:
          w:
        """
      val file = WorkloadDsl.parse(yaml)
      an[WorkloadDslException] should be thrownBy file.resolve("does-not-exist")
    }
  }

  // ── Flow types ──────────────────────────────────────────────────────────────

  "WorkloadDsl flow types" should {

    "parse a get-item flow" in {
      val yaml = """
        workloads:
          w:
            flows:
              - type: get-item
                rate: 1
        """
      val template = WorkloadDsl.parse(yaml).resolve("w")
      template.flows should have size 1
      template.flows.head.shape shouldBe TemplateShape.GetItem
    }

    "parse a delete-item flow" in {
      val yaml = """
        workloads:
          w:
            flows:
              - type: delete-item
                rate: 1
        """
      val template = WorkloadDsl.parse(yaml).resolve("w")
      template.flows.head.shape shouldBe TemplateShape.DeleteItem
    }

    "parse a put-item flow with constant item-bytes" in {
      val yaml = """
        workloads:
          w:
            flows:
              - type: put-item
                rate: 1
                item-bytes: 512
        """
      val template = WorkloadDsl.parse(yaml).resolve("w")
      val TemplateShape.PutItem(bytesSampler) = template.flows.head.shape: @unchecked
      sampleLong(bytesSampler) shouldBe 512L
    }

    "parse an update-item flow with constant item-bytes" in {
      val yaml = """
        workloads:
          w:
            flows:
              - type: update-item
                rate: 1
                item-bytes: 256
        """
      val template = WorkloadDsl.parse(yaml).resolve("w")
      val TemplateShape.UpdateItem(bytesSampler) = template.flows.head.shape: @unchecked
      sampleLong(bytesSampler) shouldBe 256L
    }

    "parse a query flow targeting the base table when no target is specified" in {
      val yaml = """
        workloads:
          w:
            flows:
              - type: query
                rate: 5
        """
      val template = WorkloadDsl.parse(yaml).resolve("w")
      val TemplateShape.Query(target, rc) = template.flows.head.shape: @unchecked
      target shouldBe UnresolvedTarget.DefaultTable
      rc     shouldBe ReadConsistency.EventuallyConsistent
    }

    "parse a query flow with a GSI index variable" in {
      val yaml = """
        workloads:
          w:
            flows:
              - type: query
                rate: 5
                target: { index: $my-gsi }
                read-consistency: strongly-consistent
        """
      val template = WorkloadDsl.parse(yaml).resolve("w")
      val TemplateShape.Query(target, rc) = template.flows.head.shape: @unchecked
      target                   shouldBe UnresolvedTarget.IndexVariable("my-gsi")
      rc                       shouldBe ReadConsistency.StronglyConsistent
      template.requiredBindings shouldBe Set("my-gsi")
    }

    "parse a scan flow targeting a GSI variable" in {
      val yaml = """
        workloads:
          w:
            flows:
              - type: scan
                rate: 2
                target: { index: $fleet-index }
        """
      val template = WorkloadDsl.parse(yaml).resolve("w")
      val TemplateShape.Scan(target, _) = template.flows.head.shape: @unchecked
      target shouldBe UnresolvedTarget.IndexVariable("fleet-index")
    }

    "parse a transact-write-items flow with multiple per-item-bytes entries" in {
      val yaml = """
        workloads:
          w:
            flows:
              - type: transact-write-items
                rate: 3
                per-item-bytes:
                  - 200
                  - 150
        """
      val template = WorkloadDsl.parse(yaml).resolve("w")
      val TemplateShape.TransactWriteItems(perItemBytes) = template.flows.head.shape: @unchecked
      perItemBytes should have size 2
      sampleLong(perItemBytes(0)) shouldBe 200L
      sampleLong(perItemBytes(1)) shouldBe 150L
    }

    "parse a transact-get-items flow with constant item-count" in {
      val yaml = """
        workloads:
          w:
            flows:
              - type: transact-get-items
                rate: 1
                item-count:
                  distribution: constant
                  value: 5
        """
      val template = WorkloadDsl.parse(yaml).resolve("w")
      val TemplateShape.TransactGetItems(itemCountSampler) = template.flows.head.shape: @unchecked
      sampleInt(itemCountSampler) shouldBe 5
    }
  }

  // ── Rate samplers ───────────────────────────────────────────────────────────

  "WorkloadDsl rate samplers" should {

    "parse an integer shorthand as a Poisson sampler with that lambda" in {
      val yaml = """
        workloads:
          w:
            flows:
              - type: get-item
                rate: 0
        """
      val template = WorkloadDsl.parse(yaml).resolve("w")
      // Poisson(0) always returns 0 — deterministic edge case
      sampleInt(template.flows.head.rate) shouldBe 0
    }

    "parse an explicit poisson distribution rate" in {
      val yaml = """
        workloads:
          w:
            flows:
              - type: get-item
                rate:
                  distribution: poisson
                  lambda: 0.0
        """
      val template = WorkloadDsl.parse(yaml).resolve("w")
      // lambda=0 → always 0
      sampleInt(template.flows.head.rate) shouldBe 0
    }

    "parse a constant distribution rate" in {
      val yaml = """
        workloads:
          w:
            flows:
              - type: get-item
                rate:
                  distribution: constant
                  value: 7
        """
      val template = WorkloadDsl.parse(yaml).resolve("w")
      sampleInt(template.flows.head.rate) shouldBe 7
    }

    "parse a binomial distribution rate" in {
      val yaml = """
        workloads:
          w:
            flows:
              - type: get-item
                rate:
                  distribution: binomial
                  n: 10
                  p: 0.0
        """
      val template = WorkloadDsl.parse(yaml).resolve("w")
      // p=0 → always 0
      sampleInt(template.flows.head.rate) shouldBe 0
    }
  }

  // ── Byte samplers ───────────────────────────────────────────────────────────

  "WorkloadDsl byte samplers" should {

    "parse an integer shorthand as ConstantSampler" in {
      val yaml = """
        workloads:
          w:
            flows:
              - type: put-item
                rate: 1
                item-bytes: 1024
        """
      val TemplateShape.PutItem(s) = WorkloadDsl.parse(yaml).resolve("w").flows.head.shape: @unchecked
      sampleLong(s) shouldBe 1024L
    }

    "parse a constant byte sampler" in {
      val yaml = """
        workloads:
          w:
            flows:
              - type: put-item
                rate: 1
                item-bytes:
                  distribution: constant
                  value: 888
        """
      val TemplateShape.PutItem(s) = WorkloadDsl.parse(yaml).resolve("w").flows.head.shape: @unchecked
      sampleLong(s) shouldBe 888L
    }

    "parse a log-normal byte sampler and produce a positive value" in {
      val yaml = """
        workloads:
          w:
            flows:
              - type: put-item
                rate: 1
                item-bytes:
                  distribution: log-normal
                  mu: 5.7
                  sigma: 0.25
        """
      val TemplateShape.PutItem(s) = WorkloadDsl.parse(yaml).resolve("w").flows.head.shape: @unchecked
      sampleLong(s) should be > 0L
    }

    "parse a normal byte sampler without throwing" in {
      val yaml = """
        workloads:
          w:
            flows:
              - type: put-item
                rate: 1
                item-bytes:
                  distribution: normal
                  mean: 500.0
                  stddev: 50.0
        """
      val TemplateShape.PutItem(s) = WorkloadDsl.parse(yaml).resolve("w").flows.head.shape: @unchecked
      noException should be thrownBy sampleLong(s)
    }

    "parse a uniform byte sampler without throwing" in {
      val yaml = """
        workloads:
          w:
            flows:
              - type: put-item
                rate: 1
                item-bytes:
                  distribution: uniform
                  min: 100.0
                  max: 1000.0
        """
      val TemplateShape.PutItem(s) = WorkloadDsl.parse(yaml).resolve("w").flows.head.shape: @unchecked
      noException should be thrownBy sampleLong(s)
    }
  }

  // ── Value expressions ───────────────────────────────────────────────────────

  "WorkloadDsl value expressions" should {

    "parse a constant float as a value expression" in {
      val yaml = """
        workloads:
          w:
            flows:
              - type: get-item
                rate:
                  distribution: poisson
                  lambda: 42.0
        """
      val PoissonSampler(lambda) = WorkloadDsl.parse(yaml).resolve("w").flows.head.rate: @unchecked
      lambda(0L)    shouldBe 42.0 +- 0.001
      lambda(999L)  shouldBe 42.0 +- 0.001
    }

    "parse a sinusoid value expression" in {
      val yaml = """
        workloads:
          w:
            flows:
              - type: get-item
                rate:
                  distribution: poisson
                  lambda:
                    shape: sinusoid
                    min: 10.0
                    max: 200.0
                    period-ticks: 100
                    peak-tick: 0
        """
      val PoissonSampler(lambda) = WorkloadDsl.parse(yaml).resolve("w").flows.head.rate: @unchecked
      lambda(0L)  shouldBe 200.0 +- 0.001   // peak
      lambda(50L) shouldBe 10.0  +- 0.001   // trough
    }

    "parse a linear-factor value expression" in {
      val yaml = """
        workloads:
          w:
            flows:
              - type: get-item
                rate:
                  distribution: poisson
                  lambda:
                    shape: linear-factor
                    rate: 0.1
        """
      val PoissonSampler(lambda) = WorkloadDsl.parse(yaml).resolve("w").flows.head.rate: @unchecked
      lambda(0L)  shouldBe 1.0 +- 0.001   // 1.0 + 0.1*0 = 1.0
      lambda(10L) shouldBe 2.0 +- 0.001   // 1.0 + 0.1*10 = 2.0
    }

    "parse a triangular-factor value expression" in {
      val yaml = """
        workloads:
          w:
            flows:
              - type: get-item
                rate:
                  distribution: poisson
                  lambda:
                    shape: triangular-factor
                    start-tick: 10
                    end-tick: 20
                    multiplier: 3.0
        """
      val PoissonSampler(lambda) = WorkloadDsl.parse(yaml).resolve("w").flows.head.rate: @unchecked
      lambda(0L)  shouldBe 1.0 +- 0.001  // outside range
      lambda(15L) shouldBe 3.0 +- 0.001  // midpoint = peak
      lambda(25L) shouldBe 1.0 +- 0.001  // outside range
    }

    "parse a weekdays value expression (1.0 on weekday, 0.0 on weekend)" in {
      val yaml = """
        workloads:
          w:
            flows:
              - type: get-item
                rate:
                  distribution: poisson
                  lambda:
                    shape: weekdays
                    ticks-per-day: 1
        """
      val PoissonSampler(lambda) = WorkloadDsl.parse(yaml).resolve("w").flows.head.rate: @unchecked
      lambda(0L) shouldBe 1.0  // Monday
      lambda(4L) shouldBe 1.0  // Friday
      lambda(5L) shouldBe 0.0  // Saturday
      lambda(6L) shouldBe 0.0  // Sunday
    }

    "parse a time-window value expression (passes inner within range, 0 outside)" in {
      val yaml = """
        workloads:
          w:
            flows:
              - type: get-item
                rate:
                  distribution: poisson
                  lambda:
                    shape: time-window
                    start-tick: 10
                    end-tick: 20
                    inner: 50.0
        """
      val PoissonSampler(lambda) = WorkloadDsl.parse(yaml).resolve("w").flows.head.rate: @unchecked
      lambda(5L)  shouldBe 0.0  +- 0.001  // before window
      lambda(10L) shouldBe 50.0 +- 0.001  // window start (inclusive)
      lambda(15L) shouldBe 50.0 +- 0.001  // inside window
      lambda(20L) shouldBe 50.0 +- 0.001  // window end (inclusive)
      lambda(21L) shouldBe 0.0  +- 0.001  // after window
    }
  }

  // ── Composition ─────────────────────────────────────────────────────────────

  "WorkloadDsl composition" should {

    "resolve a workload that includes another workload" in {
      val yaml = """
        workloads:
          base:
            flows:
              - type: get-item
                rate: 1
          composite:
            include:
              - base
            flows:
              - type: delete-item
                rate: 2
        """
      val template = WorkloadDsl.parse(yaml).resolve("composite")
      template.flows should have size 2
      template.flows(0).shape shouldBe TemplateShape.GetItem    // included first
      template.flows(1).shape shouldBe TemplateShape.DeleteItem  // own flows after
    }

    "resolve transitively included workloads and collect all flows" in {
      val yaml = """
        workloads:
          a:
            flows:
              - type: get-item
                rate: 1
          b:
            include:
              - a
            flows:
              - type: delete-item
                rate: 1
          c:
            include:
              - b
            flows:
              - type: put-item
                rate: 1
                item-bytes: 128
        """
      val template = WorkloadDsl.parse(yaml).resolve("c")
      template.flows should have size 3
      template.flows(0).shape shouldBe TemplateShape.GetItem
      template.flows(1).shape shouldBe TemplateShape.DeleteItem
      template.flows(2) shouldBe a[TemplateFlow]
    }

    "collect requiredBindings from included workloads" in {
      val yaml = """
        workloads:
          base:
            flows:
              - type: query
                rate: 5
                target: { index: $base-gsi }
          composite:
            include:
              - base
            flows:
              - type: scan
                rate: 2
                target: { index: $own-gsi }
        """
      val template = WorkloadDsl.parse(yaml).resolve("composite")
      template.requiredBindings shouldBe Set("base-gsi", "own-gsi")
    }

    "throw WorkloadDslException on circular include" in {
      val yaml = """
        workloads:
          a:
            include:
              - b
          b:
            include:
              - a
        """
      val file = WorkloadDsl.parse(yaml)
      an[WorkloadDslException] should be thrownBy file.resolve("a")
    }
  }

  // ── Bind ────────────────────────────────────────────────────────────────────

  "WorkloadTemplate bind" should {

    "bind resolves index variables to concrete GSI names" in {
      val yaml = """
        workloads:
          w:
            flows:
              - type: query
                rate: 5
                target: { index: $my-gsi }
        """
      val template = WorkloadDsl.parse(yaml).resolve("w")
      val defn     = template.bind("my-table", "my-usecase", Map("my-gsi" -> "ConcreteGSI"))
      defn.tableName shouldBe "my-table"
      defn.usecase   shouldBe "my-usecase"
      defn.flows should have size 1
      val RequestShape.Query(target, _) = defn.flows.head.shape: @unchecked
      target shouldBe DynamoDbReadTarget.GlobalSecondaryIndex("my-table", "ConcreteGSI")
    }

    "bind throws WorkloadDslException when a required binding is missing" in {
      val yaml = """
        workloads:
          w:
            flows:
              - type: query
                rate: 5
                target: { index: $my-gsi }
        """
      val template = WorkloadDsl.parse(yaml).resolve("w")
      an[WorkloadDslException] should be thrownBy template.bind("t", "u", Map.empty)
    }

    "bind a workload with only base-table flows without requiring any bindings" in {
      val yaml = """
        workloads:
          w:
            flows:
              - type: get-item
                rate: 1
              - type: put-item
                rate: 2
                item-bytes: 512
        """
      val template = WorkloadDsl.parse(yaml).resolve("w")
      template.requiredBindings shouldBe empty
      val defn = template.bind("tbl", "uc")
      defn.flows should have size 2
      defn.flows(0).shape shouldBe RequestShape.GetItem
      val RequestShape.PutItem(bytesSampler) = defn.flows(1).shape: @unchecked
      sampleLong(bytesSampler) shouldBe 512L
    }

    "bind sets the correct base-table target on query flows with no target" in {
      val yaml = """
        workloads:
          w:
            flows:
              - type: query
                rate: 1
        """
      val template = WorkloadDsl.parse(yaml).resolve("w")
      val defn     = template.bind("device-telemetry", "uc")
      val RequestShape.Query(target, _) = defn.flows.head.shape: @unchecked
      target shouldBe DynamoDbReadTarget.Table("device-telemetry")
    }
  }

  // ── Error handling ──────────────────────────────────────────────────────────

  "WorkloadDsl error handling" should {

    "throw WorkloadDslException for an unknown flow type" in {
      val yaml = """
        workloads:
          w:
            flows:
              - type: magic-item
                rate: 1
        """
      an[WorkloadDslException] should be thrownBy WorkloadDsl.parse(yaml)
    }

    "throw WorkloadDslException when put-item is missing item-bytes" in {
      val yaml = """
        workloads:
          w:
            flows:
              - type: put-item
                rate: 1
        """
      an[WorkloadDslException] should be thrownBy WorkloadDsl.parse(yaml)
    }

    "throw WorkloadDslException when target.index is not a variable reference" in {
      val yaml = """
        workloads:
          w:
            flows:
              - type: query
                rate: 1
                target: { index: hardcoded-gsi }
        """
      an[WorkloadDslException] should be thrownBy WorkloadDsl.parse(yaml)
    }

    "throw WorkloadDslException for an unknown read-consistency value" in {
      val yaml = """
        workloads:
          w:
            flows:
              - type: query
                rate: 1
                read-consistency: fully-consistent
        """
      an[WorkloadDslException] should be thrownBy WorkloadDsl.parse(yaml)
    }

    "throw WorkloadDslException when include references an unknown workload" in {
      val yaml = """
        workloads:
          w:
            include:
              - does-not-exist
        """
      val file = WorkloadDsl.parse(yaml)
      an[WorkloadDslException] should be thrownBy file.resolve("w")
    }
  }
