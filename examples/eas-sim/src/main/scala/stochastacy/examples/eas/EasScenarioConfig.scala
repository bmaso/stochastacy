package stochastacy.examples.eas

import stochastacy.aws.dynamodb.DynamoDbReadTarget
import stochastacy.aws.dynamodb.table.ReadConsistency
import stochastacy.workload.*

/**
 * Top-level configuration for a single EAS burst-scenario simulation run.
 *
 * Wraps the two per-table component configs and owns the workload builder methods,
 * following the pattern of `ThermostatFleetScenarioConfig.toWorkloadDefinition`.
 *
 * @param scenarioId       Identifier written into every exported record.
 * @param burstMultiplier  Peak-rate multiplier for A1 polling: min=400 req/tick, peak=400×M.
 *                         At M≈7.6 the GSI partition (3000 RCU/s limit) is near saturation.
 * @param simulationTicks  Total simulation ticks (1 tick = 1 second). Default 1200 (20 min).
 * @param alertsConfig     Config for the `alerts` table and its `UseCaseSampler`.
 * @param uasConfig        Config for the `user-alert-status` table and its `UseCaseSampler`.
 */
case class EasScenarioConfig(
  scenarioId:      String                   = "eas-burst",
  burstMultiplier: Double                   = 7.6,
  simulationTicks: Long                     = 1200L,
  alertsConfig:    EasAlertsConfig          = EasAlertsConfig(),
  uasConfig:       EasUserAlertStatusConfig = EasUserAlertStatusConfig()
):
  require(burstMultiplier > 0.0, "burstMultiplier must be positive")
  require(simulationTicks > 0L,  "simulationTicks must be positive")
  require(scenarioId.nonEmpty,   "scenarioId must be non-empty")

  /**
   * Alerts-table workload:
   *   - A1 baseline:  Poisson(sinusoid(400, 400×M, 900, 450)) Query on GSI by-region-index
   *   - A3 write:     Poisson(0.2 in [295,305], else 0) PutItem with constant 4500-byte items
   *   - A1 retry chain: three-attempt client-side retry with exponential backoff lag,
   *                     modelling AWS SDK default behaviour:
   *                       a1-retry-1 of a1-poll      (lag=1, proportion=0.90)
   *                       a1-retry-2 of a1-retry-1   (lag=2, proportion=0.90)
   *                       a1-retry-3 of a1-retry-2   (lag=4, proportion=0.90)
   *                     Each attempt only fires when its predecessor was throttled.  The
   *                     geometric backoff means a peak burst's retries continue echoing
   *                     for ~7 ticks past the original failure.
   *   - A2 follow-on: FollowOn of a1-poll on success, proportion=0.70, lag=1, GetItem
   */
  def toAlertsWorkload: WorkloadDefinition =
    WorkloadDefinition(
      tableName = "alerts",
      usecase   = "alerts",
      flows = Vector(
        FlowDefinition.Independent(
          id   = "a1-poll",
          defn = RequestShapeDefinition(
            rate  = PoissonSampler(
              TemporalShapeFunctions.sinusoid(400.0, 400.0 * burstMultiplier, 900L, 450L)
            ),
            shape = RequestShape.Query(
              target          = DynamoDbReadTarget.GlobalSecondaryIndex("alerts", "by-region-index"),
              readConsistency = ReadConsistency.EventuallyConsistent
            )
          )
        ),
        FlowDefinition.Independent(
          id   = "a3-write",
          defn = RequestShapeDefinition(
            rate  = PoissonSampler(tick => if tick >= 295L && tick <= 305L then 0.2 else 0.0),
            shape = RequestShape.PutItem(ConstantSampler(4500L))
          )
        ),
        FlowDefinition.Retry(
          id           = "a1-retry-1",
          sourceId     = "alerts",
          sourceFlowId = "a1-poll",
          proportion   = 0.90,
          lagTicks     = 1
        ),
        FlowDefinition.Retry(
          id           = "a1-retry-2",
          sourceId     = "alerts",
          sourceFlowId = "a1-retry-1",
          proportion   = 0.90,
          lagTicks     = 2
        ),
        FlowDefinition.Retry(
          id           = "a1-retry-3",
          sourceId     = "alerts",
          sourceFlowId = "a1-retry-2",
          proportion   = 0.90,
          lagTicks     = 4
        ),
        FlowDefinition.FollowOn(
          id           = "a2-fetch",
          sourceId     = "alerts",
          sourceFlowId = "a1-poll",
          outcome      = OutcomeFilter.Success,
          proportion   = 0.70,
          lagTicks     = 1,
          shape        = RequestShape.GetItem
        )
      )
    )

  /**
   * User-alert-status workload (open-loop, all independent):
   *   - S1 delivered:    triangular burst peaking at ~8333 req/tick at tick 320
   *   - S2 opened:       sinusoid(28, 833, 900, 480)
   *   - S3 acknowledged: sinusoid(17, 500, 900, 520)
   *
   * Item bytes sampled uniformly in [uasConfig.itemMinBytes, uasConfig.itemMaxBytes].
   */
  def toUasWorkload: WorkloadDefinition =
    val itemBytesSampler: StatelessSampler[Long] =
      Sampler.stateless((_, rng) =>
        uasConfig.itemMinBytes + rng.nextLong(uasConfig.itemMaxBytes - uasConfig.itemMinBytes + 1L)
      )
    WorkloadDefinition(
      tableName = "user-alert-status",
      usecase   = "user-alert-status",
      flows = Vector(
        FlowDefinition.Independent(
          id   = "s1-delivered",
          defn = RequestShapeDefinition(
            rate  = PoissonSampler(tick =>
              math.max(0.0, TemporalShapeFunctions.triangularFactor(280L, 360L, 8334.0)(tick) - 1.0)
            ),
            shape = RequestShape.PutItem(itemBytesSampler)
          )
        ),
        FlowDefinition.Independent(
          id   = "s2-opened",
          defn = RequestShapeDefinition(
            rate  = PoissonSampler(TemporalShapeFunctions.sinusoid(28.0, 833.0, 900L, 480L)),
            shape = RequestShape.UpdateItem(itemBytesSampler)
          )
        ),
        FlowDefinition.Independent(
          id   = "s3-acknowledged",
          defn = RequestShapeDefinition(
            rate  = PoissonSampler(TemporalShapeFunctions.sinusoid(17.0, 500.0, 900L, 520L)),
            shape = RequestShape.UpdateItem(itemBytesSampler)
          )
        )
      )
    )

object EasScenarioConfig:
  val default: EasScenarioConfig = EasScenarioConfig()
  val BaseSeed: Long = 0x4541532d42555253L  // "EAS-BURS" in ASCII
