package stochastacy.aws.examples.demo

import scala.concurrent.{ExecutionContext, Future}

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer

import stochastacy.core.run.SeedSequence

/**
 * Runs one trial of a [[MultiTableScenario]]: each table runs as an **independent leg** (its own workload,
 * `DynamoDbTable`, and accounting fold) via the shared [[TableLegRunner]], and the per-table results are
 * gathered into a [[MultiTableTrialResult]].
 *
 * Per-table seeds come from `SeedSequence.derive(seed, 3 × N)`: table `i` takes elements
 * `(3i, 3i+1, 3i+2)` as its workload / table / gate rngs. Because `derive` fills from a fresh `KISS(seed)`,
 * table 0's three seeds equal `derive(seed, 3)` for any `N`, so a table's result is independent of how many
 * other tables accompany it — and a one-table scenario matches the single-table runner exactly.
 */
final class MultiTableTrialRunner()(using ActorSystem, Materializer, ExecutionContext):

  def runTrial(scenario: MultiTableScenario, trialId: Int, seed: Long): Future[MultiTableTrialResult] =
    val seeds = SeedSequence.derive(seed, 3 * scenario.tables.size)
    val legs = scenario.tables.zipWithIndex.map { (spec, i) =>
      TableLegRunner
        .run(spec, scenario.simulationTicks, seeds(3 * i), seeds(3 * i + 1), seeds(3 * i + 2))
        .map(r => (spec.tableName, r.copy(trialId = trialId)))
    }
    Future.sequence(legs).map(perTable => MultiTableTrialResult(trialId, perTable))
