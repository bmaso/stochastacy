package stochastacy.aws.examples.demo

import scala.concurrent.{ExecutionContext, Future}

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer

import stochastacy.core.run.SeedSequence

/**
 * Runs one trial of a [[SingleTableScenario]]: derive the leg's three rngs and hand its one [[TableSpec]]
 * to the shared [[TableLegRunner]], which generates the workload, drives the `DynamoDbTable`, and folds the
 * consumption plane into a [[TrialResult]].
 *
 * `SeedSequence.derive(seed, 3)` yields the workload / table / gate seeds — unchanged from before the
 * multi-table generalization (the multi-table runner derives `3 × N` and slices, whose first three elements
 * are these exact seeds), so single-table output is byte-identical.
 */
final class SingleTableTrialRunner()(using ActorSystem, Materializer, ExecutionContext):

  def runTrial(scenario: SingleTableScenario, trialId: Int, seed: Long): Future[TrialResult] =
    val Vector(workloadSeed, tableSeed, gateSeed) = SeedSequence.derive(seed, 3): @unchecked
    TableLegRunner
      .run(scenario.tableSpec, scenario.simulationTicks, workloadSeed, tableSeed, gateSeed)
      .map(_.copy(trialId = trialId))
