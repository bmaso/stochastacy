package stochastacy.examples.thermostatfleet

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Path}

class ThermostatFleetMixedModeGrafanaAssetsSpec extends AnyWordSpec with should.Matchers:

  "Thermostat Fleet Mixed Billing Mode Grafana dashboard" should {

    "exist in the repo" in {
      Files.exists(Path.of("examples/grafana/thermostat-fleet-mixed-mode-dashboard.json")) shouldBe true
    }

    "be valid JSON with expected top-level keys" in {
      val json = Files.readString(Path.of("examples/grafana/thermostat-fleet-mixed-mode-dashboard.json"))
      json should include("\"title\"")
      json should include("\"uid\"")
      json should include("\"panels\"")
      json should include("\"templating\"")
    }

    "declare the expected dashboard uid and title" in {
      val json = Files.readString(Path.of("examples/grafana/thermostat-fleet-mixed-mode-dashboard.json"))
      json should include("\"ips-phase4-mixed-mode\"")
      json should include("Thermostat Fleet Mixed Billing Mode Demo")
    }

    "include the billing mode and throttling panels" in {
      val json = Files.readString(Path.of("examples/grafana/thermostat-fleet-mixed-mode-dashboard.json"))
      json should include("Billing Mode Timeline")
      json should include("Throttle Rate")
    }

    "include the capacity utilization panels" in {
      val json = Files.readString(Path.of("examples/grafana/thermostat-fleet-mixed-mode-dashboard.json"))
      json should include("Write Capacity: Consumed vs. Provisioned")
      json should include("Read Capacity: Consumed vs. Provisioned")
    }

    "include the cost trajectory panel" in {
      val json = Files.readString(Path.of("examples/grafana/thermostat-fleet-mixed-mode-dashboard.json"))
      json should include("Cumulative Estimated Cost")
    }

    "reference the mixed-mode-specific metric names" in {
      val json = Files.readString(Path.of("examples/grafana/thermostat-fleet-mixed-mode-dashboard.json"))
      json should include("BillingModeIndicator")
      json should include("ThrottleCount")
      json should include("ProvisionedWriteCapacityUnits")
      json should include("ProvisionedReadCapacityUnits")
    }

    "declare required template variables" in {
      val json = Files.readString(Path.of("examples/grafana/thermostat-fleet-mixed-mode-dashboard.json"))
      json should include("\"batch_id\"")
      json should include("\"windowSizeSeconds\"")
      json should include("\"scenarioId\"")
      json should include("\"trialCount\"")
      json should include("\"simulationTicks\"")
      json should include("\"totalEstimatedCostCentralRange\"")
    }
  }
