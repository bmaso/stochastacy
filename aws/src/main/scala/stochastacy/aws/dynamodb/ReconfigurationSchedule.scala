package stochastacy.aws.dynamodb

/** A management action applied to a table at a scheduled tick: switch its billing mode, or (while
 *  provisioned) change its provisioned capacity. */
enum ReconfigurationEvent:
  case SwitchBillingMode(newMode: BillingMode)
  case UpdateProvisionedCapacity(newCapacity: BillingMode.Provisioned)

/** One scheduled reconfiguration: apply `event` at the tick boundary of `tick`. */
final case class ScheduledReconfiguration(tick: Long, event: ReconfigurationEvent):
  require(tick > 0L, "reconfiguration tick must be positive")

/**
 * A table's reconfiguration schedule — billing-mode switches and provisioned-capacity updates applied at
 * chosen tick boundaries (the static counterpart to what an auto-scaler will drive reactively). The mode in
 * force at a tick is a pure fold of the entries up to that tick over the initial mode, so the table (via
 * `onTick`) and the accounting compute it identically from the same schedule.
 */
final case class ReconfigurationSchedule(entries: Vector[ScheduledReconfiguration] = Vector.empty):
  require(
    entries.map(_.tick).zip(entries.drop(1).map(_.tick)).forall((l, r) => l < r),
    "ReconfigurationSchedule entries must be ordered by strictly increasing tick"
  )

  /** The billing mode in force at `tick`: the initial mode with every entry scheduled at or before `tick`
   *  applied in order. */
  def billingModeAt(tick: Long, initial: BillingMode): BillingMode =
    entries.filter(_.tick <= tick).foldLeft(initial)((m, e) => ReconfigurationSchedule.applyEvent(m, e.event))

  /** Validate the schedule against the table's initial mode and horizon — mirrors the legacy guards:
   *  entries within the horizon, a 24 h cooldown between billing-mode switches, and capacity updates only
   *  while the table is provisioned at that point. */
  def validate(initial: BillingMode, simulationTicks: Long): Either[String, ReconfigurationSchedule] =
    if simulationTicks < 1L then Left(s"simulationTicks must be at least 1, got $simulationTicks")
    else if entries.exists(_.tick > simulationTicks) then Left(s"reconfiguration ticks must be <= simulationTicks ($simulationTicks)")
    else
      def step(state: Either[String, (BillingMode, Option[Long])], e: ScheduledReconfiguration): Either[String, (BillingMode, Option[Long])] =
        state.flatMap { (mode, lastSwitch) =>
          e.event match
            case ReconfigurationEvent.SwitchBillingMode(nm) =>
              lastSwitch match
                case Some(prev) if e.tick - prev < ReconfigurationSchedule.CooldownTicks =>
                  Left(s"billing-mode switches must be at least ${ReconfigurationSchedule.CooldownTicks} ticks apart")
                case _ => Right((nm, Some(e.tick)))
            case ReconfigurationEvent.UpdateProvisionedCapacity(cap) =>
              mode match
                case _: BillingMode.Provisioned => Right((cap, lastSwitch))
                case BillingMode.OnDemand       => Left("UpdateProvisionedCapacity requires the table to be provisioned at that tick")
        }
      entries.foldLeft[Either[String, (BillingMode, Option[Long])]](Right((initial, None)))(step).map(_ => this)

object ReconfigurationSchedule:
  /** DynamoDB's 24-hour cooldown between billing-mode switches (one tick = one second). */
  val CooldownTicks: Long = 86400L

  val empty: ReconfigurationSchedule = ReconfigurationSchedule()

  def applyEvent(mode: BillingMode, event: ReconfigurationEvent): BillingMode = event match
    case ReconfigurationEvent.SwitchBillingMode(newMode)          => newMode
    case ReconfigurationEvent.UpdateProvisionedCapacity(capacity) => capacity
