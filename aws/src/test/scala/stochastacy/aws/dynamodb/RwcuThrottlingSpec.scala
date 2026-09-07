package stochastacy.aws.dynamodb

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.aws.dynamodb.TableMechanics.OperationOutcome
import stochastacy.core.component.Timed
import stochastacy.core.sampler.LogNormalSampler
import stochastacy.sim.{SimTime, TimedControlEvent, TimedElement, ticks}

/**
 * rWCU throttling at the coordinator (phase-11 Slice 3): a destination's inbound rWCU ceiling drains its
 * source streams fair-share per tick, so under depletion `PendingReplicationCount` grows and the measured
 * `ReplicationLatency` = link lag + backlog wait — the heavier source stream diverging above the lighter one,
 * both draining on recovery. The ceiling is never exceeded; no ceiling reproduces the Slice-2 (pure-lag) behaviour.
 */
class RwcuThrottlingSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("RwcuThrottlingSpec")
  private given Materializer         = Materializer.matFromSystem
  private given ExecutionContext     = system.dispatcher
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)
  private def await[A](f: Future[A]): A = Await.result(f, 60.seconds)

  private val WriteBytes = 500L                         // 500 B → 1 rWCU per write (ceil(500/1024))
  private val model      = ReplicationModel(default = Some(LogNormalSampler.constant(math.log(1.5), 0.0))) // lag ⌊1.5⌋ = 1
  private def rng: UniformRandomProvider = RandomSource.KISS.create(11L)

  /** Build a coordinator input stream: for each tick `1..maxTick`, a `Tick` followed by the taps scheduled
   *  that tick (`arrivals(tick)` = a list of `(sourceRegion, count)`), terminated by `EndOfTime`. */
  private def input(maxTick: Long, arrivals: Map[Long, List[(String, Int)]]): Vector[TimedElement[Timed[TaggedTap]]] =
    (1L to maxTick).toVector.flatMap { t =>
      val tick: TimedElement[Timed[TaggedTap]] = TimedControlEvent.Tick(SimTime.of(t))
      val taps: Vector[TimedElement[Timed[TaggedTap]]] =
        arrivals.getOrElse(t, Nil).toVector.flatMap { case (src, count) =>
          Vector.fill(count)(Timed(TaggedTap(src, ReplicationWrite(OperationOutcome.Put(WriteBytes, None))), SimTime.of(t), 0.0, "w"))
        }
      tick +: taps
    } :+ TimedControlEvent.EndOfTime

  private def run(
    regions:  Vector[String],
    ceilings: Map[String, Option[Long]],
    in:       Vector[TimedElement[Timed[TaggedTap]]]
  ): Vector[Timed[ReplicationOutput]] =
    await(Source(in).via(ReplicationCoordinator.flow(regions, model, ceilings, rng)).runWith(Sink.seq))
      .collect { case t: Timed[ReplicationOutput] @unchecked => t }.toVector

  private def pendingSeries(out: Vector[Timed[ReplicationOutput]], src: String, dst: String): Vector[(Long, Long)] =
    out.collect { case Timed(ReplicationOutput.Pending(PendingReplicationSample(s, d, n)), et, _, _) if s == src && d == dst => (et.ticks, n) }

  private def maxLatency(out: Vector[Timed[ReplicationOutput]], src: String, dst: String): Long =
    out.collect { case Timed(ReplicationOutput.Latency(ReplicationLatencySample(s, d, l)), _, _, _) if s == src && d == dst => l }
      .maxOption.getOrElse(0L)

  "An rWCU ceiling below the inbound rate" should {
    "grow the backlog while overloaded, then drain it to zero on recovery" in {
      // A → B, ceiling 1 rWCU/tick; A sends 3 writes/tick for ticks 1..5 (3 in, 1 out), then silence.
      // 15 writes back up and drain at 1/tick, clearing by ~tick 16.
      val in  = input(maxTick = 20L, arrivals = (1L to 5L).map(_ -> List("A" -> 3)).toMap)
      val out = run(Vector("A", "B"), Map("B" -> Some(1L)), in)
      val ab  = pendingSeries(out, "A", "B").toMap

      ab(5L)  should be > ab(1L)        // backlog climbs while overloaded
      ab(5L)  should be > 5L
      ab(20L) shouldBe 0L               // fully drained once input stops (1 rWCU/tick clears the tail)
    }
  }

  "Two source streams sharing one destination ceiling" should {
    "diverge — the heavier stream builds the deeper, slower queue (fair-share)" in {
      // A (heavy, 8/tick) and D (light, 1/tick) → C; ceiling 2 rWCU/tick ⇒ round-robin admits 1 each/tick.
      val arrivals = (1L to 10L).map(_ -> List("A" -> 8, "D" -> 1)).toMap
      val out      = run(Vector("A", "C", "D"), Map("C" -> Some(2L)), input(maxTick = 12L, arrivals))

      val heavy = pendingSeries(out, "A", "C").toMap
      val light = pendingSeries(out, "D", "C").toMap
      heavy(10L)        should be > (light(10L) * 5L)   // the heavy backlog dwarfs the light one
      maxLatency(out, "A", "C") should be > maxLatency(out, "D", "C") // and waits far longer
      light.values.max  should be <= 2L                 // the light stream keeps up (≈ link lag)
    }
  }

  "The fair-share drain" should {
    "never release more than the ceiling's rWCU in any tick" in {
      val arrivals = (1L to 10L).map(_ -> List("A" -> 8, "D" -> 1)).toMap
      val out      = run(Vector("A", "C", "D"), Map("C" -> Some(2L)), input(maxTick = 12L, arrivals))

      val perTickReleasedRwcu: Map[Long, Long] =
        out.collect { case Timed(ReplicationOutput.Transfer(CrossRegionTransferEvent(_, "C", b)), et, _, _) => (et.ticks, b) }
          .groupMapReduce(_._1)(t => ThroughputMath.writeCapacityUnits(t._2).toLong)(_ + _)
      perTickReleasedRwcu.values.foreach(_ should be <= 2L)
    }
  }

  "No ceiling (unlimited rWCU)" should {
    "release every eligible write at its link lag — the Slice-2 behaviour" in {
      // 5 writes at tick 1, no ceiling ⇒ all released at tick 2 (lag 1), each latency == 1.
      val out = run(Vector("A", "B"), Map.empty, input(maxTick = 4L, arrivals = Map(1L -> List("A" -> 5))))

      val released = out.collect { case Timed(ReplicationOutput.ReplicatedWriteFor("B", _), et, _, _) => et.ticks }
      released                       shouldBe Vector.fill(5)(2L) // all at tick 2, none held back
      maxLatency(out, "A", "B")      shouldBe 1L                 // measured latency == link lag
      pendingSeries(out, "A", "B").toMap.apply(2L) shouldBe 0L   // nothing pending after release
    }
  }
