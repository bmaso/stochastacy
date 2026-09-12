package stochastacy.examples.grafana

import java.nio.file.{Files, Path}

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

/**
 * Locks the Grafana pipeline assets the v2 bridge lands on — the docker-compose stack, the Postgres schema,
 * Grafana provisioning, and the order-tracking dashboards — plus the panel titles and
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

    "ship the thermostat-fleet dashboard adapted to v2's metric set (capacity/storage/cost/GSI, no unsupported panels)" in {
      val json = read("examples/grafana/thermostat-fleet-dashboard.json")
      json should include ("Total Read Capacity Units by Window")
      json should include ("GSI ${gsiIndexName}: Read Capacity Units by Window")
      json should include ("Storage Bytes by Window")
      // dropped — metrics v2 does not (yet) produce for a single-region cost run:
      json should not include ("Latency Percentiles")
      json should not include ("by Region")
      json should not include ("System Error Count")
    }

    "ship the thermostat-mixed-mode dashboard with the right-sizing-trap panels (billing mode / provisioned / throttle)" in {
      val json = read("examples/grafana/thermostat-fleet-mixed-mode-dashboard.json")
      json should include ("Billing Mode Timeline")
      json should include ("Throttle Rate")
      json should include ("Consumed vs. Provisioned")
      json should include ("BillingModeIndicator")
      json should include ("ProvisionedReadCapacityUnits")
      // dropped:
      json should not include ("Latency Percentiles")
      json should not include ("Admitted vs. Throttled Requests")
    }

    "ship the thermostat multi-table dashboard with its per-table window + cost panels" in {
      val json = read("examples/grafana/thermostat-fleet-multi-table-dashboard.json")
      json should include ("Read Capacity Units by Window")
      json should include ("Total Cost per Table")
      json should include ("Table:device-registry:ReadCapacityUnits")
      json should include ("Table:device-telemetry:WriteCapacityUnits")
    }

    "ship the capstone dashboard with the append-only Events TTL showcase + per-table series" in {
      val json = read("examples/grafana/thermostat-fleet-capstone-dashboard.json")
      // TTL is demonstrated on the append-only device-events table (the telemetry table's saturated fleet never ages out):
      json should include ("Device Events: TTL Deleted Item Count per Window")
      json should include ("Table:device-events:TimeToLiveDeletedItemCount")
      json should include ("Device Events: Storage (TTL-bounded plateau)")
      json should include ("Table:device-events:StorageBytes")
      // device-events joins the per-table panels:
      json should include ("Table:device-events:CumulativeEstimatedCost")
      json should include ("Table:device-events:WriteCapacityUnits")
      // the TTL panel no longer points at the (inert) telemetry TTL metric:
      json should not include ("Table:device-telemetry:TimeToLiveDeletedItemCount")
      json should include ("Throttle Count per Table per Window")
      json should include ("Table:device-telemetry:ProvisionedWriteCapacityUnits")
      // dropped — the item-count *stock* (only a special-API estimate, not a native metric) and Tier-2/3 panels:
      json should not include ("EstimatedItemCount")
      json should not include ("Latency Percentiles")
      json should not include ("System Error Count")
    }
  }
