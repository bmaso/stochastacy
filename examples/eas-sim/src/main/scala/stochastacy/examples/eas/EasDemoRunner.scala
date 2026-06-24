package stochastacy.examples.eas

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Source => PekkoSource}
import org.json4s.jackson.Serialization
import stochastacy.demo.{DemoExportRecord, IncrementalMonteCarloAgg, IncrementalWindowedAgg, TimeWindowRollups, TrialExecutionConfig, WindowSizeSeconds}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
 * Generates a JSONL export file from a Monte Carlo run of the EAS burst scenario.
 *
 * Follows the same streaming incremental pattern as `OrderTrackingPhase2DemoRunner` and
 * `ThermostatFleetDemoRunner`: trials are run concurrently via `mapAsync`, each result is
 * serialized immediately (per-trial + windowed records), then discarded — so memory footprint
 * is O(ticks × metrics) regardless of trial count. Aggregate records (mean/stddev) are written
 * after all trials complete.
 */
object EasDemoRunner:

  def generateToFile(
    config:      EasScenarioConfig,
    outputPath:  String,
    trialCount:  Int,
    parallelism: Int
  )(using ActorSystem, Materializer, ExecutionContext): Future[String] =

    given org.json4s.DefaultFormats = org.json4s.DefaultFormats

    val runner = EasSingleTrialRunner()
    val exec   = TrialExecutionConfig(trialCount, parallelism, EasScenarioConfig.BaseSeed)

    val writer = new java.io.BufferedWriter(
      new java.io.OutputStreamWriter(
        java.nio.file.Files.newOutputStream(java.nio.file.Path.of(outputPath)),
        java.nio.charset.StandardCharsets.UTF_8
      )
    )

    case class AggState(
      mcAgg:          IncrementalMonteCarloAgg,
      windowedAgg:    Map[WindowSizeSeconds, IncrementalWindowedAgg],
      recordCount:    Int,
      completedTrials: Int
    )

    val initState = AggState(
      mcAgg        = IncrementalMonteCarloAgg(config.scenarioId),
      windowedAgg  = WindowSizeSeconds.phase1Values.map(ws => ws -> IncrementalWindowedAgg(ws)).toMap,
      recordCount  = 0,
      completedTrials = 0
    )

    def writeRecord(rec: DemoExportRecord): Unit =
      writer.write(Serialization.write(rec))
      writer.newLine()

    val barWidth = 40
    def printProgress(completed: Int): Unit =
      val pct    = if trialCount == 0 then 100 else (completed * 100) / trialCount
      val filled = if trialCount == 0 then barWidth else (completed * barWidth) / trialCount
      val bar    = "█" * filled + "░" * (barWidth - filled)
      print(s"\r[$bar] $completed/$trialCount ($pct%)")
      System.out.flush()

    printProgress(0)

    PekkoSource(exec.trialRunConfigs)
      .mapAsync(parallelism)(run => runner.runTrial(config, run))
      .runFold(initState) { (state, trial) =>
        val perTrialRecs =
          DemoExportRecord.fromTrialResult(trial) ++
            WindowSizeSeconds.phase1Values.flatMap { ws =>
              DemoExportRecord.fromWindowedTrialTimeSeries(
                trial.scenarioId, trial.trialId,
                TimeWindowRollups.rollupTrialTimeSeries(trial.timeSeries, ws)
              )
            }
        perTrialRecs.foreach(writeRecord)
        val newCompleted = state.completedTrials + 1
        printProgress(newCompleted)
        state.copy(
          mcAgg        = state.mcAgg.addTrial(trial),
          windowedAgg  = state.windowedAgg.map { case (ws, wagg) => ws -> wagg.addTrial(trial.timeSeries) },
          recordCount  = state.recordCount + perTrialRecs.size,
          completedTrials = newCompleted
        )
      }
      .map { finalState =>
        val mcResult = finalState.mcAgg.toMonteCarloResult
        val aggRecs  =
          DemoExportRecord.fromMonteCarloResult(mcResult) ++
            WindowSizeSeconds.phase1Values.flatMap { ws =>
              DemoExportRecord.fromAggregatedWindowedTimeSeries(
                mcResult.scenarioId, mcResult.trialCount,
                finalState.windowedAgg(ws).toAggregatedWindowedPoints
              )
            }
        aggRecs.foreach(writeRecord)
        writer.flush()
        writer.close()
        println()
        s"wrote ${finalState.recordCount + aggRecs.size} records for scenario ${mcResult.scenarioId} to $outputPath"
      }
      .andThen { case scala.util.Failure(_) => println(); Try(writer.close()) }(ExecutionContext.parasitic)
