package stochastacy.examples.grafana

import java.nio.file.{Files, Path}

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

/**
 * Locks the Grafana pipeline assets the v2 bridge lands on — the docker-compose stack, the Postgres schema,
 * Grafana provisioning, and the (reused, legacy-matched) order-tracking dashboards — plus the panel titles and
 * metric selectors each dashboard queries. Pure file-content assertions; the live round-trip is
 * [[GrafanaBridgeSpec]] (against H2). Slices 2–3 extend this with the thermostat dashboards.
 */
class GrafanaAssetsSpec extends AnyWordSpec with should.Matchers:

  private def read(path: String): String =
    val p = Path.of(path)
    Files.exists(p) shouldBe true
    Files.readString(p)

  "The Grafana pipeline assets" should {
    "provide the docker-compose stack, schema, and Grafana provisioning" in {
      Seq(
        "docker-compose.yml",
        "examples/postgres/init/001-schema.sql",
        "examples/src/main/resources/stochastacy/demo/postgres/001-schema.sql",
        "examples/grafana/provisioning/datasources/stochastacy-postgres.yaml",
        "examples/grafana/provisioning/dashboards/stochastacy-dashboards.yaml"
      ).foreach(p => withClue(s"$p: ")(Files.exists(Path.of(p)) shouldBe true))
    }

    "ship the order-tracking phase-1 dashboard with its window + summary panels" in {
      val json = read("examples/grafana/order-tracking-phase1-dashboard.json")
      json should include ("Read Capacity Units by Window")
      json should include ("Write Capacity Units by Window")
      json should include ("Total Estimated Cost Central Range")
      json should include ("Final Storage Bytes Central Range")
      json should include ("'ReadCapacityUnits'")
      json should include ("'TotalEstimatedCost'")
    }

    "ship the order-tracking phase-2 dashboard with its per-GSI panels" in {
      val json = read("examples/grafana/order-tracking-phase2-dashboard.json")
      json should include ("gsiIndexName")
      json should include ("GSI Read Capacity Units by Window")
      json should include ("GSI Write Capacity Units by Window")
      json should include (":ReadCapacityUnits")
    }
  }
