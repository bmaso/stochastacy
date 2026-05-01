package stochastacy.examples.thermostatfleet

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.Path
import java.time.{ZoneOffset, ZonedDateTime}

class ThermostatFleetBridgeSpec extends AnyWordSpec with should.Matchers:

  private val fixedNow = ZonedDateTime.of(2026, 4, 26, 12, 0, 0, 0, ZoneOffset.UTC)

  "ThermostatFleetBridgeCli" should {

    "parse a valid generate command (single-region)" in {
      val result = ThermostatFleetBridgeCli.parseArgs(
        Seq("generate", "--output", "/tmp/out.jsonl", "--mode", "single-region"),
        fixedNow
      )

      result shouldBe a[Right[?, ?]]
      val cmd = result.toOption.get.asInstanceOf[ThermostatFleetBridgeCommand.Generate]
      cmd.outputPath shouldBe Path.of("/tmp/out.jsonl")
      cmd.mode shouldBe "single-region"
      cmd.trialCount should be > 0
      cmd.parallelism should be > 0
      cmd.simulationTicks should be > 0L
    }

    "parse a valid generate command (multi-region) with all flags" in {
      val result = ThermostatFleetBridgeCli.parseArgs(
        Seq("generate", "--output", "/tmp/out.jsonl", "--mode", "multi-region",
          "--batch-id", "batch-001", "--trial-count", "5", "--parallelism", "2", "--simulation-ticks", "30"),
        fixedNow
      )

      result shouldBe a[Right[?, ?]]
      val cmd = result.toOption.get.asInstanceOf[ThermostatFleetBridgeCommand.Generate]
      cmd.batchId shouldBe "batch-001"
      cmd.mode shouldBe "multi-region"
      cmd.trialCount shouldBe 5
      cmd.parallelism shouldBe 2
      cmd.simulationTicks shouldBe 30L
    }

    "reject generate with missing --output" in {
      val result = ThermostatFleetBridgeCli.parseArgs(Seq("generate", "--mode", "single-region"), fixedNow)
      result shouldBe a[Left[?, ?]]
      result.left.toOption.get should include("--output")
    }

    "reject generate with missing --mode" in {
      val result = ThermostatFleetBridgeCli.parseArgs(Seq("generate", "--output", "/tmp/out.jsonl"), fixedNow)
      result shouldBe a[Left[?, ?]]
      result.left.toOption.get should include("--mode")
    }

    "reject generate with invalid --mode" in {
      val result = ThermostatFleetBridgeCli.parseArgs(
        Seq("generate", "--output", "/tmp/out.jsonl", "--mode", "global"),
        fixedNow
      )
      result shouldBe a[Left[?, ?]]
      result.left.toOption.get should include("--mode")
    }

    "reject generate with non-integer --trial-count" in {
      val result = ThermostatFleetBridgeCli.parseArgs(
        Seq("generate", "--output", "/tmp/out.jsonl", "--mode", "single-region", "--trial-count", "abc"),
        fixedNow
      )
      result shouldBe a[Left[?, ?]]
    }

    "parse a valid stage command" in {
      val result = ThermostatFleetBridgeCli.parseArgs(
        Seq("stage", "--input", "/tmp/out.jsonl", "--batch-id", "batch-001",
          "--db-url", "jdbc:postgresql://localhost/test", "--db-user", "user", "--db-password", "pass",
          "--trial-count", "5", "--parallelism", "2", "--simulation-ticks", "30")
      )

      result shouldBe a[Right[?, ?]]
      val cmd = result.toOption.get.asInstanceOf[ThermostatFleetBridgeCommand.Stage]
      cmd.inputPath shouldBe Path.of("/tmp/out.jsonl")
      cmd.metadata.batchId shouldBe "batch-001"
      cmd.metadata.trialCount shouldBe 5
    }

    "reject stage with missing --input" in {
      val result = ThermostatFleetBridgeCli.parseArgs(
        Seq("stage", "--batch-id", "b", "--db-url", "u", "--db-user", "u", "--db-password", "p",
          "--trial-count", "1", "--parallelism", "1", "--simulation-ticks", "1")
      )
      result shouldBe a[Left[?, ?]]
      result.left.toOption.get should include("--input")
    }

    "parse a valid view command" in {
      val result = ThermostatFleetBridgeCli.parseArgs(
        Seq("view", "--batch-id", "batch-001", "--mode", "single-region")
      )

      result shouldBe a[Right[?, ?]]
      val cmd = result.toOption.get.asInstanceOf[ThermostatFleetBridgeCommand.View]
      cmd.batchId shouldBe "batch-001"
      cmd.scenarioId shouldBe ThermostatFleetScenarioConfig.singleRegionDefault.scenarioId
    }

    "parse view with multi-region mode" in {
      val result = ThermostatFleetBridgeCli.parseArgs(
        Seq("view", "--batch-id", "batch-002", "--mode", "multi-region")
      )

      result shouldBe a[Right[?, ?]]
      val cmd = result.toOption.get.asInstanceOf[ThermostatFleetBridgeCommand.View]
      cmd.scenarioId shouldBe ThermostatFleetScenarioConfig.multiRegionDefault.scenarioId
    }

    "reject view with missing --batch-id" in {
      val result = ThermostatFleetBridgeCli.parseArgs(Seq("view"))
      result shouldBe a[Left[?, ?]]
      result.left.toOption.get should include("--batch-id")
    }

    "reject unknown subcommand" in {
      val result = ThermostatFleetBridgeCli.parseArgs(Seq("deploy"))
      result shouldBe a[Left[?, ?]]
      result.left.toOption.get should include("unknown subcommand")
    }

    "return usage when no args provided" in {
      val result = ThermostatFleetBridgeCli.parseArgs(Seq.empty)
      result shouldBe a[Left[?, ?]]
    }
  }
