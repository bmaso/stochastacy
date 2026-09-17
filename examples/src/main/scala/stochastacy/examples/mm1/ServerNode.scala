package stochastacy.examples.mm1

import org.apache.commons.rng.UniformRandomProvider

import stochastacy.core.component.{ComponentSampler, Emission, Scheduled, TickEmission}
import stochastacy.sim.SimInstant

/** One job the server has accepted: when it arrived, when it starts, and when it finishes — all known at arrival. */
private[mm1] final case class Job(arrival: Double, start: Double, finish: Double)

/** `freeAt` is when the server next becomes idle; `open` holds the jobs that may still overlap a future window. */
final case class ServerState(freeAt: Double, open: Vector[Job])

object ServerState:
  val initial: ServerState = ServerState(0.0, Vector.empty)

/**
 * The single FIFO server. A request arriving at `a` starts at `max(a, freeAt)` and takes `Exp(serviceRate)`, so its
 * **queue wait is computed exactly** rather than sampled, and the response delay is the whole sojourn.
 *
 * Because every job's finish time is known when it arrives, the trajectory of N(t) over a closed tick window is fully
 * determined by the jobs seen so far — so `onTick` integrates it exactly: `inSystem` sums each open job's overlap of
 * `[arrival, finish]` with the window (that integral *is* ∫N dt) and `busy` sums the overlap of `[start, finish]`.
 * Jobs that can no longer overlap a later window are dropped, so `open` stays bounded by the in-flight count.
 */
final class ServerNode(config: MM1Config) extends ComponentSampler[ServerState, PageRequest, PageResponse, MM1Fact]:

  def initialState: ServerState = ServerState.initial

  def sample(in: PageRequest, at: SimInstant, state: ServerState, rng: UniformRandomProvider)
      : Emission[ServerState, PageResponse, MM1Fact] =
    val arrival = at.toDouble
    val start   = math.max(arrival, state.freeAt)
    val finish  = start + Exponential.draw(config.serviceRate, rng)
    val sojourn = finish - arrival
    Emission(
      ServerState(finish, state.open :+ Job(arrival, start, finish)),
      Scheduled(PageResponse(in.session, in.page, in.sessionStartedAt), sojourn),
      List(Scheduled(MM1Fact.PageServed(sojourn), sojourn)) // stamped at completion
    )

  /** At the boundary opening tick `t`, the window `[t − 1, t)` has just closed and every job that could touch it is
   *  known — integrate it, then forget the jobs that ended inside it. */
  override def onTick(tick: Long, state: ServerState): TickEmission[ServerState, MM1Fact] =
    if tick <= 1L then TickEmission(state, Nil) // no window has closed yet
    else
      val from = (tick - 1L).toDouble
      val to   = tick.toDouble
      var inSystem = 0.0
      var busy     = 0.0
      state.open.foreach { job =>
        inSystem += overlap(job.arrival, job.finish, from, to)
        busy     += overlap(job.start, job.finish, from, to)
      }
      TickEmission(
        state.copy(open = state.open.filter(_.finish > to)),
        List(
          Scheduled(MM1Fact.WindowIntegral(tick - 1L, inSystem, busy), 0.0),
          Scheduled(MM1Fact.WindowLevels(tick - 1L, levelTimes(state.open, from, to)), 0.0)
        )
      )

  /** Walk N(t) across `[from, to)` as a step function and total the time spent at each level. Start from the jobs in
   *  the system at `from`; each arrival inside the window steps N up and each finish inside it steps N down. At equal
   *  instants arrivals go first — any order takes zero time, but this keeps N from dipping below zero mid-tie. */
  private def levelTimes(open: Vector[Job], from: Double, to: Double): Vector[Double] =
    val slots = new Array[Double](config.queueLevels)
    val top   = config.queueLevels - 1
    var level = open.count(job => job.arrival < from && job.finish > from)
    val steps = open.flatMap { job =>
      val up   = if job.arrival >= from && job.arrival < to then Vector((job.arrival, 1)) else Vector.empty
      val down = if job.finish > from && job.finish < to then Vector((job.finish, -1)) else Vector.empty
      up ++ down
    }.sortBy((time, delta) => (time, -delta))
    var at = from
    steps.foreach { (time, delta) =>
      slots(math.min(level, top)) += time - at
      at = time
      level += delta
    }
    slots(math.min(level, top)) += to - at
    slots.toVector

  private def overlap(from: Double, to: Double, windowFrom: Double, windowTo: Double): Double =
    math.max(0.0, math.min(to, windowTo) - math.max(from, windowFrom))
