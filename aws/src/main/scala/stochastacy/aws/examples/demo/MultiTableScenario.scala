package stochastacy.aws.examples.demo

/**
 * A multi-table demo scenario: the ensemble parameters plus several independent [[TableSpec]]s composed
 * into one simulation. Each table runs its own workload against its own `DynamoDbTable`; they share only
 * the ensemble (`simulationTicks` / `trialCount` / `parallelism`) and are reported **per table**
 * (`Table:<name>:…`). Table names must be distinct.
 */
trait MultiTableScenario:
  def scenarioId:      String
  def simulationTicks: Long
  def trialCount:      Int
  def parallelism:     Int
  def tables:          Vector[TableSpec]

/** The result of one multi-table trial: one [[TrialResult]] per table, in table order. */
final case class MultiTableTrialResult(
  trialId:  Int,
  perTable: Vector[(String, TrialResult)]
)
