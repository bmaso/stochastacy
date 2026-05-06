package stochastacy.aws.dynamodb.autoscaling

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.{Actor, ActorRef, ActorSystem, Props}
import org.apache.pekko.stream.{BoundedSourceQueue, Materializer}
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import stochastacy.aws.dynamodb.table.{
  AdmissionMetricEvent,
  DynamoDbManagementEvent,
  DynamoDbTable,
  TableMetricEvent
}
import stochastacy.sim.{SimTime, TimedControlEvent, TimedElement, ticks}

object DynamoDbAutoScaler:
  final case class Policy(
    targetUtilization: Double,
    evaluationWindowTicks: Int,
    scaleUpReactionDelayTicks: Int,
    scaleDownReactionDelayTicks: Int,
    scaleUpCooldownTicks: Int,
    scaleDownCooldownTicks: Int,
    scaleDownThresholdFactor: Double = 0.5,
    minReadCapacityUnits: Long,
    maxReadCapacityUnits: Long,
    minWriteCapacityUnits: Long,
    maxWriteCapacityUnits: Long
  )

  private[autoscaling] case object StreamComplete
  private[autoscaling] final case class StreamFailed(cause: Throwable)
  private[autoscaling] object Usecase

final class DynamoDbAutoScaler(
  policy: DynamoDbAutoScaler.Policy,
  initialMode: DynamoDbTable.BillingMode.Provisioned
)(using system: ActorSystem, mat: Materializer):
  import DynamoDbAutoScaler.*

  private val (queue: BoundedSourceQueue[TimedElement[DynamoDbManagementEvent]], preMatSource) =
    Source.queue[TimedElement[DynamoDbManagementEvent]](64).preMaterialize()

  private val actorRef: ActorRef =
    system.actorOf(Props(new AutoScalerActor(policy, initialMode, queue)))

  val managementSource: Source[TimedElement[DynamoDbManagementEvent], NotUsed] = preMatSource

  val metricSink: Sink[TimedElement[TableMetricEvent], NotUsed] =
    Sink.actorRef(actorRef, StreamComplete, StreamFailed(_))

  def stop(): Unit = system.stop(actorRef)

// ── Private actor ────────────────────────────────────────────────────────────

private[autoscaling] class AutoScalerActor(
  policy: DynamoDbAutoScaler.Policy,
  initialMode: DynamoDbTable.BillingMode.Provisioned,
  queue: BoundedSourceQueue[TimedElement[DynamoDbManagementEvent]]
) extends Actor:
  import DynamoDbAutoScaler.*

  private var isProvisioned            = true
  private var currentProvisioned       = initialMode
  private var readUtilWindow           = Vector.empty[Double]
  private var writeUtilWindow          = Vector.empty[Double]
  private var pendingReadDecision      = Option.empty[(Long, Long)]  // (fireTick, newRCU)
  private var pendingWriteDecision     = Option.empty[(Long, Long)]  // (fireTick, newWCU)
  private var lastReadScaleTick: Long  = Long.MinValue / 2
  private var lastWriteScaleTick: Long = Long.MinValue / 2
  private var currentTick: Long        = 0L

  override def receive: Receive =
    case tick: TimedControlEvent.Tick =>
      currentTick = tick.eventTime.ticks
      drainPendingDecisions(tick.eventTime)

    case u: AdmissionMetricEvent.ProvisionedCapacityUtilization if isProvisioned =>
      currentProvisioned = currentProvisioned.copy(
        readCapacityUnits  = u.provisionedReadCapacityUnits,
        writeCapacityUnits = u.provisionedWriteCapacityUnits
      )
      processUtilization(u)

    case b: AdmissionMetricEvent.BillingModeSnapshot =>
      isProvisioned = b.billingModeCode == 1
      if !isProvisioned then
        readUtilWindow        = Vector.empty
        writeUtilWindow       = Vector.empty
        pendingReadDecision   = None
        pendingWriteDecision  = None

    case StreamComplete    => queue.complete()
    case _: StreamFailed   => queue.complete()
    case _                 => ()

  private def drainPendingDecisions(eventTime: SimTime): Unit =
    pendingReadDecision.foreach { case (fireTick, newRCU) =>
      if currentTick >= fireTick then
        val newMode = currentProvisioned.copy(readCapacityUnits = newRCU)
        queue.offer(DynamoDbManagementEvent.UpdateProvisionedCapacity(eventTime, Usecase, newMode))
        currentProvisioned   = newMode
        pendingReadDecision  = None
    }
    pendingWriteDecision.foreach { case (fireTick, newWCU) =>
      if currentTick >= fireTick then
        val newMode = currentProvisioned.copy(writeCapacityUnits = newWCU)
        queue.offer(DynamoDbManagementEvent.UpdateProvisionedCapacity(eventTime, Usecase, newMode))
        currentProvisioned    = newMode
        pendingWriteDecision  = None
    }

  private def processUtilization(u: AdmissionMetricEvent.ProvisionedCapacityUtilization): Unit =
    processOneDimension(
      consumed           = u.consumedReadUnits.toDouble,
      provisioned        = u.provisionedReadCapacityUnits,
      window             = readUtilWindow,
      pending            = pendingReadDecision,
      lastScaleTick      = lastReadScaleTick,
      minCap             = policy.minReadCapacityUnits,
      maxCap             = policy.maxReadCapacityUnits,
      upDelay            = policy.scaleUpReactionDelayTicks,
      downDelay          = policy.scaleDownReactionDelayTicks,
      upCooldown         = policy.scaleUpCooldownTicks,
      downCooldown       = policy.scaleDownCooldownTicks,
      currentCap         = currentProvisioned.readCapacityUnits,
      setWindow          = w => readUtilWindow = w,
      setPending         = p => pendingReadDecision = p,
      setLastScaleTick   = t => lastReadScaleTick = t
    )
    processOneDimension(
      consumed           = u.consumedWriteUnits.toDouble,
      provisioned        = u.provisionedWriteCapacityUnits,
      window             = writeUtilWindow,
      pending            = pendingWriteDecision,
      lastScaleTick      = lastWriteScaleTick,
      minCap             = policy.minWriteCapacityUnits,
      maxCap             = policy.maxWriteCapacityUnits,
      upDelay            = policy.scaleUpReactionDelayTicks,
      downDelay          = policy.scaleDownReactionDelayTicks,
      upCooldown         = policy.scaleUpCooldownTicks,
      downCooldown       = policy.scaleDownCooldownTicks,
      currentCap         = currentProvisioned.writeCapacityUnits,
      setWindow          = w => writeUtilWindow = w,
      setPending         = p => pendingWriteDecision = p,
      setLastScaleTick   = t => lastWriteScaleTick = t
    )

  private def processOneDimension(
    consumed: Double,
    provisioned: Long,
    window: Vector[Double],
    pending: Option[(Long, Long)],
    lastScaleTick: Long,
    minCap: Long,
    maxCap: Long,
    upDelay: Int,
    downDelay: Int,
    upCooldown: Int,
    downCooldown: Int,
    currentCap: Long,
    setWindow: Vector[Double] => Unit,
    setPending: Option[(Long, Long)] => Unit,
    setLastScaleTick: Long => Unit
  ): Unit =
    val util = if provisioned > 0L then consumed / provisioned else 0.0
    val newWindow = (window :+ util).takeRight(policy.evaluationWindowTicks)
    setWindow(newWindow)

    if newWindow.size >= policy.evaluationWindowTicks && pending.isEmpty then
      val avg = newWindow.sum / newWindow.size
      if avg > policy.targetUtilization &&
         (currentTick - lastScaleTick) >= upCooldown then
        val target = math.ceil(consumed / policy.targetUtilization).toLong
        val newCap = math.min(target, maxCap)
        if newCap > currentCap then
          setPending(Some((currentTick + upDelay, newCap)))
          setWindow(Vector.empty)
          setLastScaleTick(currentTick)
      else if avg < policy.targetUtilization * policy.scaleDownThresholdFactor &&
              (currentTick - lastScaleTick) >= downCooldown then
        val target = math.ceil(consumed / policy.targetUtilization).toLong
        val newCap = math.max(target, minCap)
        if newCap < currentCap then
          setPending(Some((currentTick + downDelay, newCap)))
          setWindow(Vector.empty)
          setLastScaleTick(currentTick)
