package stochastacy.workload

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class WorkloadEvaluatorSpec extends AnyWordSpec with should.Matchers:

  private val simpleYaml =
    """
      |workloads:
      |  simple:
      |    flows:
      |      - type: put-item
      |        rate:
      |          distribution: constant
      |          value: 3
      |        item-bytes: 512
      |      - type: get-item
      |        rate:
      |          distribution: poisson
      |          lambda: 5.0
      |      - type: update-item
      |        rate:
      |          distribution: constant
      |          value: 1
      |        item-bytes: 512
      |      - type: delete-item
      |        rate:
      |          distribution: constant
      |          value: 1
      |""".stripMargin

  private val allTypesYaml =
    """
      |workloads:
      |  all-types:
      |    flows:
      |      - type: get-item
      |        rate: 1
      |      - type: delete-item
      |        rate: 1
      |      - type: put-item
      |        rate: 1
      |        item-bytes: 512
      |      - type: update-item
      |        rate: 1
      |        item-bytes: 512
      |      - type: query
      |        rate: 1
      |      - type: scan
      |        rate: 1
      |      - type: transact-write-items
      |        rate: 1
      |        per-item-bytes:
      |          - 200
      |      - type: transact-get-items
      |        rate: 1
      |        item-count: 2
      |""".stripMargin

  "WorkloadEvaluator" should {

    "return the correct number of shapes for a 4-flow workload" in {
      val result = WorkloadEvaluator.evaluate(simpleYaml, "simple", 10L, 42L)
      result.shapes should have size 4
    }

    "return correct requestType strings for all 8 flow types" in {
      val result = WorkloadEvaluator.evaluate(allTypesYaml, "all-types", 1L, 42L)
      result.shapes.map(_.requestType) shouldBe Vector(
        "get-item", "delete-item", "put-item", "update-item",
        "query", "scan", "transact-write-items", "transact-get-items"
      )
    }

    "return tickCount × shapeCount samples" in {
      val result = WorkloadEvaluator.evaluate(simpleYaml, "simple", 10L, 42L)
      result.samples should have size (10 * 4)
    }

    "produce the constant count at every tick for a constant-rate sampler" in {
      val result    = WorkloadEvaluator.evaluate(simpleYaml, "simple", 20L, 42L)
      val putCounts = result.samples.filter(_.shapeIndex == 0).map(_.count)
      putCounts.forall(_ == 3) shouldBe true
    }

    "produce different counts for different seeds with a Poisson sampler" in {
      val r1       = WorkloadEvaluator.evaluate(simpleYaml, "simple", 50L, 1L)
      val r2       = WorkloadEvaluator.evaluate(simpleYaml, "simple", 50L, 2L)
      val counts1  = r1.samples.filter(_.shapeIndex == 1).map(_.count)
      val counts2  = r2.samples.filter(_.shapeIndex == 1).map(_.count)
      counts1 should not equal counts2
    }
  }
