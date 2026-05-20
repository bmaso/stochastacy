package stochastacy.visualizer

import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class WorkloadVisualizerServerSpec extends AnyWordSpec with should.Matchers with ScalatestRouteTest:

  private val server    = new WorkloadVisualizerServer()
  private val testRoute = server.route

  private val oneFlowYaml =
    """
      |workloads:
      |  simple:
      |    flows:
      |      - type: get-item
      |        rate:
      |          distribution: constant
      |          value: 1
      |""".stripMargin

  "WorkloadVisualizerServer" should {

    "return 200 with JSON for POST /api/evaluate with valid YAML" in {
      Post("/api/evaluate?workload=simple&ticks=10&seed=42").withEntity(oneFlowYaml) ~> testRoute ~> check {
        status           shouldBe StatusCodes.OK
        contentType      shouldBe ContentTypes.`application/json`
        val body = responseAs[String]
        body should include("\"shapes\"")
        body should include("\"samples\"")
        body should include("get-item")
      }
    }

    "return 400 for POST /api/evaluate with an unknown workload name" in {
      Post("/api/evaluate?workload=nonexistent&ticks=10&seed=42").withEntity(oneFlowYaml) ~> testRoute ~> check {
        status shouldBe StatusCodes.BadRequest
      }
    }

    "return 200 with HTML content for GET /" in {
      Get("/") ~> testRoute ~> check {
        status                    shouldBe StatusCodes.OK
        contentType.mediaType     shouldBe MediaTypes.`text/html`
      }
    }
  }
