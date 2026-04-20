package stochastacy.examples.ordertracking

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Path}

class OrderTrackingGrafanaAssetsSpec extends AnyWordSpec with should.Matchers:

  "Phase-2 Docker and Grafana assets" should {
    "exist in the repo" in {
      Files.exists(Path.of("docker-compose.yml")) shouldBe true
      Files.exists(Path.of("examples/postgres/init/001-schema.sql")) shouldBe true
      Files.exists(Path.of("examples/grafana/provisioning/datasources/stochastacy-postgres.yaml")) shouldBe true
      Files.exists(Path.of("examples/grafana/provisioning/dashboards/stochastacy-dashboards.yaml")) shouldBe true
      Files.exists(Path.of("examples/grafana/order-tracking-phase2-dashboard.json")) shouldBe true
    }

    "include the per-GSI dashboard selector and panels" in {
      val dashboardJson = Files.readString(Path.of("examples/grafana/order-tracking-phase2-dashboard.json"))

      dashboardJson should include("gsiIndexName")
      dashboardJson should include("Total Read Capacity Units by Window")
      dashboardJson should include("Total Write Capacity Units by Window")
      dashboardJson should include("GSI Read Capacity Units by Window")
      dashboardJson should include("GSI Write Capacity Units by Window")
      dashboardJson should include("GSI Read Capacity Units Central Range")
      dashboardJson should include("GSI Write Capacity Units Central Range")
    }
  }
