package stochastacy.examples.ordertracking

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Path}

class OrderTrackingGrafanaAssetsSpec extends AnyWordSpec with should.Matchers:

  "Phase-1 Docker and Grafana assets" should {
    "exist in the repo" in {
      Files.exists(Path.of("docker-compose.yml")) shouldBe true
      Files.exists(Path.of("examples/postgres/init/001-schema.sql")) shouldBe true
      Files.exists(Path.of("examples/grafana/provisioning/datasources/stochastacy-postgres.yaml")) shouldBe true
      Files.exists(Path.of("examples/grafana/provisioning/dashboards/stochastacy-dashboards.yaml")) shouldBe true
      Files.exists(Path.of("examples/grafana/order-tracking-phase1-dashboard.json")) shouldBe true
    }
  }
