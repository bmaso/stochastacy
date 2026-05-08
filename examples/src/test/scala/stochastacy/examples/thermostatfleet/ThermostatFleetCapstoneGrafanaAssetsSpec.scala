package stochastacy.examples.thermostatfleet

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Path}

class ThermostatFleetCapstoneGrafanaAssetsSpec extends AnyWordSpec with should.Matchers:

  "Thermostat Fleet Capstone Grafana dashboard" should {

    "exist in the repo" in {
      Files.exists(Path.of("examples/grafana/thermostat-fleet-capstone-dashboard.json")) shouldBe true
    }

    "be valid JSON with expected top-level keys" in {
      val json = Files.readString(Path.of("examples/grafana/thermostat-fleet-capstone-dashboard.json"))
      json should include("\"title\"")
      json should include("\"uid\"")
      json should include("\"panels\"")
      json should include("\"templating\"")
    }

    "declare the expected dashboard uid and title" in {
      val json = Files.readString(Path.of("examples/grafana/thermostat-fleet-capstone-dashboard.json"))
      json should include("\"ips-phase6-capstone\"")
      json should include("Thermostat Fleet — Capstone Demo")
    }

    "include cost panels for all four tables" in {
      val json = Files.readString(Path.of("examples/grafana/thermostat-fleet-capstone-dashboard.json"))
      json should include("Cumulative Estimated Cost by Table")
      json should include("Mean Total Estimated Cost per Table")
      json should include("Table:device-registry:CumulativeEstimatedCost")
      json should include("Table:device-telemetry:CumulativeEstimatedCost")
      json should include("Table:device-commands:CumulativeEstimatedCost")
      json should include("Table:device-alerts:CumulativeEstimatedCost")
    }

    "include the auto-scaling provisioned capacity panel" in {
      val json = Files.readString(Path.of("examples/grafana/thermostat-fleet-capstone-dashboard.json"))
      json should include("WCU Consumed vs Provisioned")
      json should include("Table:device-telemetry:ProvisionedWriteCapacityUnits")
    }

    "include the throttle count panel" in {
      val json = Files.readString(Path.of("examples/grafana/thermostat-fleet-capstone-dashboard.json"))
      json should include("Throttle Count per Table")
      json should include("Table:device-telemetry:ThrottleCount")
    }

    "include the system error count panel" in {
      val json = Files.readString(Path.of("examples/grafana/thermostat-fleet-capstone-dashboard.json"))
      json should include("System Error Count per Table")
      json should include("Table:device-telemetry:SystemErrorCount")
      json should include("Table:device-registry:SystemErrorCount")
    }

    "include the TTL estimated item count panel" in {
      val json = Files.readString(Path.of("examples/grafana/thermostat-fleet-capstone-dashboard.json"))
      json should include("Estimated Live Item Count")
      json should include("Table:device-telemetry:EstimatedItemCount")
    }

    "include storage and capacity panels" in {
      val json = Files.readString(Path.of("examples/grafana/thermostat-fleet-capstone-dashboard.json"))
      json should include("Storage Bytes by Table")
      json should include("WCU Consumed by Table")
      json should include("RCU Consumed by Table")
      json should include("Table:device-registry:StorageBytes")
      json should include("Table:device-telemetry:WriteCapacityUnits")
    }

    "use the correct database schema in all SQL queries" in {
      val json = Files.readString(Path.of("examples/grafana/thermostat-fleet-capstone-dashboard.json"))
      json should include("stochastacy_demo.trial_window_time_series")
      json should include("stochastacy_demo.trial_summary")
      json should include("stochastacy_demo.demo_batches")
      json should not include "windowed_time_series"
      json should not include "batch_runs"
    }

    "declare required template variables with correct types" in {
      val json = Files.readString(Path.of("examples/grafana/thermostat-fleet-capstone-dashboard.json"))
      json should include("\"batch_id\"")
      json should include("\"windowSizeSeconds\"")
      json should include("\"scenarioId\"")
      json should include("\"trialCount\"")
      json should include("\"simulationTicks\"")
      json should include("\"type\": \"textbox\"")
    }
  }
