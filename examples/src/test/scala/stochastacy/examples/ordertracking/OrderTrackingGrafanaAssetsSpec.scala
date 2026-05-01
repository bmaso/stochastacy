package stochastacy.examples.ordertracking

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Path}

class OrderTrackingGrafanaAssetsSpec extends AnyWordSpec with should.Matchers:

  "Phase-1 and phase-2 Docker and Grafana assets" should {
    "exist in the repo" in {
      Files.exists(Path.of("docker-compose.yml")) shouldBe true
      Files.exists(Path.of("examples/postgres/init/001-schema.sql")) shouldBe true
      Files.exists(Path.of("examples/grafana/provisioning/datasources/stochastacy-postgres.yaml")) shouldBe true
      Files.exists(Path.of("examples/grafana/provisioning/dashboards/stochastacy-dashboards.yaml")) shouldBe true
      Files.exists(Path.of("examples/grafana/order-tracking-phase1-dashboard.json")) shouldBe true
      Files.exists(Path.of("examples/grafana/order-tracking-phase2-dashboard.json")) shouldBe true
    }

    "include the restored phase-1 dashboard panels" in {
      val dashboardJson = Files.readString(Path.of("examples/grafana/order-tracking-phase1-dashboard.json"))

      dashboardJson should include("Order Tracking Phase 1")
      dashboardJson should include("Read Capacity Units by Window")
      dashboardJson should include("Write Capacity Units by Window")
      dashboardJson should include("Total Estimated Cost Central Range")
      dashboardJson should include("Final Storage Bytes Central Range")
    }

    "include the per-GSI dashboard selector and panels" in {
      val dashboardJson = Files.readString(Path.of("examples/grafana/order-tracking-phase2-dashboard.json"))

      dashboardJson should include("gsiIndexName")
      dashboardJson should include("Total Read Capacity Units by Window")
      dashboardJson should include("Total Write Capacity Units by Window")
      dashboardJson should include("GSI Read Capacity Units by Window")
      dashboardJson should include("GSI Write Capacity Units by Window")
      dashboardJson should include("Mean expected requests per second: 8.05")
      dashboardJson should include("pay-per-request style pricing")
    }
  }
