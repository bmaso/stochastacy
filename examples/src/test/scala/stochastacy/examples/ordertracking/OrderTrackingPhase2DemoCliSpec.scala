package stochastacy.examples.ordertracking

import org.scalatest.EitherValues.*
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.Path
import java.time.{ZoneOffset, ZonedDateTime}

class OrderTrackingPhase2DemoCliSpec extends AnyWordSpec with should.Matchers:

  private val fixedNow = ZonedDateTime.of(2026, 4, 18, 10, 45, 0, 0, ZoneOffset.UTC)

  "OrderTrackingPhase2BridgeCli" should {
    "parse generate with defaults and derived batch id" in {
      OrderTrackingPhase2BridgeCli.parseArgs(
        Seq("generate", "--output", "/tmp/demo.jsonl"),
        now = fixedNow
      ) shouldBe Right(
        OrderTrackingBridgeCommand.Generate(
          batchId = "order-tracking-phase2-20260418104500",
          outputPath = Path.of("/tmp/demo.jsonl"),
          trialCount = OrderTrackingScenarioConfig.phase2Default.trialCount,
          parallelism = OrderTrackingScenarioConfig.phase2Default.parallelism,
          simulationTicks = OrderTrackingScenarioConfig.phase2Default.simulationTicks,
          startEpochSeconds = OrderTrackingPhase2BridgeCli.DefaultStartEpochSeconds
        )
      )
    }

    "parse generate overrides" in {
      OrderTrackingPhase2BridgeCli.parseArgs(
        Seq(
          "generate",
          "--output", "/tmp/demo.jsonl",
          "--batch-id", "batch-1",
          "--trial-count", "4",
          "--parallelism", "2",
          "--simulation-ticks", "12"
        ),
        now = fixedNow
      ) shouldBe Right(
        OrderTrackingBridgeCommand.Generate(
          batchId = "batch-1",
          outputPath = Path.of("/tmp/demo.jsonl"),
          trialCount = 4,
          parallelism = 2,
          simulationTicks = 12L,
          startEpochSeconds = OrderTrackingPhase2BridgeCli.DefaultStartEpochSeconds
        )
      )
    }

    "parse stage command" in {
      OrderTrackingPhase2BridgeCli.parseArgs(
        Seq(
          "stage",
          "--input", "/tmp/demo.jsonl",
          "--batch-id", "batch-1",
          "--db-url", "jdbc:postgresql://localhost:5432/stochastacy_demo",
          "--db-user", "stochastacy",
          "--db-password", "secret",
          "--trial-count", "4",
          "--parallelism", "2",
          "--simulation-ticks", "12"
        ),
        now = fixedNow
      ) shouldBe Right(
        OrderTrackingBridgeCommand.Stage(
          inputPath = Path.of("/tmp/demo.jsonl"),
          metadata = BatchMetadata(
            batchId = "batch-1",
            scenarioId = OrderTrackingScenarioConfig.phase2Default.scenarioId,
            trialCount = 4,
            parallelism = 2,
            simulationTicks = 12L,
            baseSeed = OrderTrackingPhase2DemoRunner.Phase2BaseSeed,
            readConsistency = OrderTrackingScenarioConfig.phase2Default.readConsistency.toString,
            tableName = OrderTrackingScenarioConfig.phase2Default.tableName,
            sourceJsonlPath = Some("/tmp/demo.jsonl")
          ),
          dbUrl = "jdbc:postgresql://localhost:5432/stochastacy_demo",
          dbUser = "stochastacy",
          dbPassword = "secret"
        )
      )
    }

    "parse view command" in {
      OrderTrackingPhase2BridgeCli.parseArgs(
        Seq("view", "--batch-id", "batch-1"),
        now = fixedNow
      ) shouldBe Right(
        OrderTrackingBridgeCommand.View(
          grafanaBaseUrl = "http://localhost:3000",
          batchId = "batch-1",
          scenarioId = OrderTrackingScenarioConfig.phase2Default.scenarioId
        )
      )
    }

    "reject unknown flags" in {
      val error = OrderTrackingPhase2BridgeCli.parseArgs(Seq("generate", "--bogus", "x"), now = fixedNow).left.value
      error should include("unknown flag")
    }

    "reject missing flag values" in {
      val error = OrderTrackingPhase2BridgeCli.parseArgs(Seq("view", "--batch-id"), now = fixedNow).left.value
      error should include("missing value")
    }

    "reject duplicate flags" in {
      val error = OrderTrackingPhase2BridgeCli.parseArgs(
        Seq("generate", "--output", "/tmp/a", "--output", "/tmp/b"),
        now = fixedNow
      ).left.value
      error should include("duplicate flag")
    }
  }
