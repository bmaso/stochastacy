package stochastacy.aws.dynamodb

/**
 * Reactive (target-tracking) auto-scaling for a provisioned table's **base** read/write capacity — a
 * faithful port of the legacy `DynamoDbAutoScaler.Policy` logic, but as pure, immutable table mechanics run
 * in `onTick` rather than a separate actor/stream. Each dimension (read, write) tracks a rolling utilization
 * window; when the average crosses the scale-up / scale-down threshold (and the cooldown has elapsed) it
 * schedules a capacity change that takes effect after a reaction delay — scale-up-fast, scale-down-slow.
 */
final case class AutoScalingPolicy(
  targetUtilization:           Double,
  evaluationWindowTicks:       Int,
  scaleUpReactionDelayTicks:   Int,
  scaleDownReactionDelayTicks: Int,
  scaleUpCooldownTicks:        Int,
  scaleDownCooldownTicks:      Int,
  minReadCapacityUnits:        Long,
  maxReadCapacityUnits:        Long,
  minWriteCapacityUnits:       Long,
  maxWriteCapacityUnits:       Long,
  scaleDownThresholdFactor:    Double = 0.5
):
  require(targetUtilization > 0.0 && targetUtilization <= 1.0, "targetUtilization must be in (0, 1]")
  require(evaluationWindowTicks >= 1,                          "evaluationWindowTicks must be at least 1")
  require(scaleDownThresholdFactor > 0.0 && scaleDownThresholdFactor <= 1.0, "scaleDownThresholdFactor must be in (0, 1]")
  require(minReadCapacityUnits >= 1L && maxReadCapacityUnits >= minReadCapacityUnits,   "read capacity bounds must satisfy 1 <= min <= max")
  require(minWriteCapacityUnits >= 1L && maxWriteCapacityUnits >= minWriteCapacityUnits, "write capacity bounds must satisfy 1 <= min <= max")

/** One dimension's auto-scaling state: the rolling utilization window, a scheduled-but-not-yet-applied
 *  capacity change `(fireTick, newCapacity)`, and the tick of the last scaling decision (for the cooldown). */
final case class AutoScalingDimensionState(
  window:        Vector[Double]       = Vector.empty,
  pending:       Option[(Long, Long)] = None,
  lastScaleTick: Long                 = Long.MinValue / 2
)

/** A table's auto-scaling state, per dimension. */
final case class AutoScalingState(
  read:  AutoScalingDimensionState = AutoScalingDimensionState(),
  write: AutoScalingDimensionState = AutoScalingDimensionState()
)

object AutoScalingState:
  val initial: AutoScalingState = AutoScalingState()

object AutoScaler:

  /**
   * Advance one tick boundary. `budget` is the just-completed tick's admitted capacity (its base-target
   * tallies are the "consumed" signal); `current` is the capacity that was in force during that tick.
   * Returns the capacity for the new tick (a due pending change applied) and the advanced state.
   */
  def step(
    policy:  AutoScalingPolicy,
    tick:    Long,
    current: BillingMode.Provisioned,
    budget:  ThrottleBudget,
    state:   AutoScalingState
  ): (BillingMode.Provisioned, AutoScalingState) =
    val (newRead, readState) = stepDimension(
      policy, tick,
      consumed      = budget.read.getOrElse(ThrottleBudget.BaseKey, BigDecimal(0)),
      currentCap    = current.readCapacityUnits,
      minCap        = policy.minReadCapacityUnits,
      maxCap        = policy.maxReadCapacityUnits,
      dim           = state.read
    )
    val (newWrite, writeState) = stepDimension(
      policy, tick,
      consumed      = budget.write.getOrElse(ThrottleBudget.BaseKey, BigDecimal(0)),
      currentCap    = current.writeCapacityUnits,
      minCap        = policy.minWriteCapacityUnits,
      maxCap        = policy.maxWriteCapacityUnits,
      dim           = state.write
    )
    (current.copy(readCapacityUnits = newRead, writeCapacityUnits = newWrite), AutoScalingState(readState, writeState))

  /** The per-dimension control loop: record utilization, apply a due pending change, then maybe schedule a
   *  new one. Returns the dimension's capacity for the new tick and its advanced state. */
  private def stepDimension(
    policy:     AutoScalingPolicy,
    tick:       Long,
    consumed:   BigDecimal,
    currentCap: Long,
    minCap:     Long,
    maxCap:     Long,
    dim:        AutoScalingDimensionState
  ): (Long, AutoScalingDimensionState) =
    val util      = if currentCap > 0L then (consumed / BigDecimal(currentCap)).toDouble else 0.0
    val window    = (dim.window :+ util).takeRight(policy.evaluationWindowTicks)

    // Apply a due pending decision (it becomes the capacity in force for this tick onward).
    val (capNow, pendingAfter) = dim.pending match
      case Some((fireTick, newCap)) if fireTick <= tick => (newCap, None)
      case other                                        => (currentCap, other)

    val idle = AutoScalingDimensionState(window, pendingAfter, dim.lastScaleTick)

    if window.size < policy.evaluationWindowTicks || pendingAfter.isDefined then
      (capNow, idle)
    else
      val avg    = window.sum / window.size
      val target = math.ceil(consumed.toDouble / policy.targetUtilization).toLong
      if avg > policy.targetUtilization && (tick - dim.lastScaleTick) >= policy.scaleUpCooldownTicks then
        val newCap = math.min(target, maxCap)
        if newCap > capNow then (capNow, AutoScalingDimensionState(Vector.empty, Some((tick + policy.scaleUpReactionDelayTicks, newCap)), tick))
        else (capNow, idle)
      else if avg < policy.targetUtilization * policy.scaleDownThresholdFactor && (tick - dim.lastScaleTick) >= policy.scaleDownCooldownTicks then
        val newCap = math.max(target, minCap)
        if newCap < capNow then (capNow, AutoScalingDimensionState(Vector.empty, Some((tick + policy.scaleDownReactionDelayTicks, newCap)), tick))
        else (capNow, idle)
      else
        (capNow, idle)
