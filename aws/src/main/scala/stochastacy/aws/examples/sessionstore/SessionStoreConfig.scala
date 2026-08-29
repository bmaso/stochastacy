package stochastacy.aws.examples.sessionstore

import org.apache.commons.rng.UniformRandomProvider

import stochastacy.aws.dynamodb.{DynamoDbRequest, GlobalSecondaryIndex, IndexProjection, LocalSecondaryIndex, TableBehavior, TableSummaryState}
import stochastacy.aws.examples.demo.SingleTableScenario
import stochastacy.core.component.Timed

/**
 * A **session-store** scenario, purpose-built to exercise TTL: a login service that writes one session
 * item per sign-in and validates sessions by key. Every write is a **new** session (a fresh id, never an
 * overwrite), so items **accumulate** — and a fixed idle-timeout TTL (`ttlPeriodTicks`) expires each
 * session that many ticks after it was written. This is the canonical DynamoDB TTL pattern: with TTL on,
 * stored bytes climb for `ttlPeriodTicks` ticks and then **plateau** (creates per tick ≈ expiries per
 * tick); with TTL off they rise unbounded.
 *
 *   - **create** — `sessionsPerTick` new sessions per tick (Poisson), each `sessionBytes` bytes, inserted;
 *   - **validate** — `validationsPerTick` `GetItem`s per tick (Poisson) against the table;
 *   - one KeysOnly GSI `user-sessions` (sessions by user), so the demo exercises **base + index** TTL
 *     freeing end-to-end, not just the base table.
 *
 * On-demand billing, no throttling, no reconfiguration — the scenario isolates the TTL storage effect.
 */
final case class SessionStoreConfig(
  scenarioId:         String      = "session-store",
  simulationTicks:    Long        = 1800L,
  trialCount:         Int         = 100,
  parallelism:        Int         = 4,
  sessionsPerTick:    Double      = 40.0,
  validationsPerTick: Double      = 20.0,
  sessionBytes:       Long        = 400L,
  override val ttlPeriodTicks: Option[Int] = Some(600)
) extends SingleTableScenario:
  require(scenarioId.nonEmpty,                    "scenarioId must be non-empty")
  require(simulationTicks >= 1L,                  "simulationTicks must be at least 1")
  require(trialCount >= 1,                        "trialCount must be at least 1")
  require(parallelism >= 1,                       "parallelism must be at least 1")
  require(sessionsPerTick >= 0.0,                 "sessionsPerTick must be non-negative")
  require(validationsPerTick >= 0.0,              "validationsPerTick must be non-negative")
  require(sessionBytes >= 1L,                     "sessionBytes must be at least 1")
  require(ttlPeriodTicks.forall(_ >= 1),          "ttlPeriodTicks, when set, must be at least 1")

  def globalSecondaryIndexes: Vector[GlobalSecondaryIndex] = Vector(
    GlobalSecondaryIndex(SessionStoreConfig.UserSessionsGsiName, IndexProjection.KeysOnly)
  )
  def localSecondaryIndexes: Vector[LocalSecondaryIndex] = Vector.empty

  /** The table starts empty and fills as sessions are created. */
  def initialTableState: TableSummaryState = TableSummaryState.empty
  def initialStorageBytesAllTargets: Long  = 0L

  def behavior: TableBehavior = new SessionStoreBehavior()

  def arrivals(rng: UniformRandomProvider): Vector[Timed[DynamoDbRequest]] =
    SessionStoreWorkload.arrivals(this, rng)

object SessionStoreConfig:
  val UserSessionsGsiName = "user-sessions"

  /** The shipped preset: a 600-tick idle timeout over an 1800-tick run, so the storage plateau is plainly
   *  visible (climbs to ~tick 600, then flat). */
  val default: SessionStoreConfig = SessionStoreConfig()
