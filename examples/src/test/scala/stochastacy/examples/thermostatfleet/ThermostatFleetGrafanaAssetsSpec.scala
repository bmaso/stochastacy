package stochastacy.examples.thermostatfleet

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Path}

class ThermostatFleetGrafanaAssetsSpec extends AnyWordSpec with should.Matchers:

  "Thermostat Fleet Grafana dashboard" should {

    "exist in the repo" in {
      Files.exists(Path.of("examples/grafana/thermostat-fleet-dashboard.json")) shouldBe true
    }

    "be valid JSON with expected top-level keys" in {
      val json = Files.readString(Path.of("examples/grafana/thermostat-fleet-dashboard.json"))
      json should include("\"title\"")
      json should include("\"uid\"")
      json should include("\"panels\"")
      json should include("\"templating\"")
    }

    "declare the expected dashboard uid and title" in {
      val json = Files.readString(Path.of("examples/grafana/thermostat-fleet-dashboard.json"))
      json should include("\"ips-phase3-thermostat-fleet\"")
      json should include("Thermostat Fleet DynamoDB Demo")
    }

    "include capacity overview panels" in {
      val json = Files.readString(Path.of("examples/grafana/thermostat-fleet-dashboard.json"))
      json should include("Total Read Capacity Units by Window")
      json should include("Total Write Capacity Units by Window")
    }

    "include per-region panels" in {
      val json = Files.readString(Path.of("examples/grafana/thermostat-fleet-dashboard.json"))
      json should include("Write Capacity Units by Region")
      json should include("Read Capacity Units by Region")
      json should include("Cumulative Estimated Cost by Region")
      json should include("Region:us-east-1:WriteCapacityUnits")
      json should include("Region:us-east-1:CumulativeEstimatedCost")
    }

    "include GSI pressure panels" in {
      val json = Files.readString(Path.of("examples/grafana/thermostat-fleet-dashboard.json"))
      json should include("gsiIndexName")
      json should include("GSI:${gsiIndexName}:WriteCapacityUnits")
      json should include("GSI:${gsiIndexName}:ReadCapacityUnits")
    }

    "include storage and cost panels" in {
      val json = Files.readString(Path.of("examples/grafana/thermostat-fleet-dashboard.json"))
      json should include("Storage Bytes by Window")
      json should include("Cumulative Estimated Cost by Window")
      json should include("Cross-Region Transfer Bytes")
      json should include("Cumulative Cross-Region Transfer Cost")
    }

    "include replication latency panel" in {
      val json = Files.readString(Path.of("examples/grafana/thermostat-fleet-dashboard.json"))
      json should include("ReplicationLatencyMs")
      json should include("Region:us-east-1:ReplicationLatencyMs")
    }

    "include latency percentile panels for PutItem, Query, and Scan" in {
      val json = Files.readString(Path.of("examples/grafana/thermostat-fleet-dashboard.json"))
      json should include("PutItem Latency Percentiles")
      json should include("Query Latency Percentiles")
      json should include("Scan Latency Percentiles")
      json should include("LatencyP50:Query")
      json should include("LatencyP50:Scan")
    }

    "declare required template variables" in {
      val json = Files.readString(Path.of("examples/grafana/thermostat-fleet-dashboard.json"))
      json should include("\"batch_id\"")
      json should include("\"windowSizeSeconds\"")
      json should include("\"scenarioId\"")
      json should include("\"trialCount\"")
      json should include("\"simulationTicks\"")
      json should include("\"gsiIndexName\"")
      json should include("\"regionName\"")
      json should include("\"totalEstimatedCostCentralRange\"")
      json should include("\"finalStorageBytesCentralRange\"")
    }
  }
