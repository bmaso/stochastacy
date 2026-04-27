package stochastacy.aws.dynamodb.table

import org.apache.commons.rng.simple.RandomSource
import org.apache.commons.statistics.distribution.{ContinuousDistribution, LogNormalDistribution}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.*
import stochastacy.aws.dynamodb.table.LogicalPartitionAccess.SingleLogicalPartitionKey
import stochastacy.aws.transfer.CrossRegionTransferEvent
import stochastacy.sim.{SimTime, TimedControlEvent, TimedElement, TimedEvent, ticks}

import scala.collection.immutable.SortedMap
import scala.concurrent.duration.*
import scala.concurrent.{Await, Future}

class ReplicationCoordinatorSpec extends AnyWordSpec with should.Matchers:

  given ActorSystem = ActorSystem("replication-coordinator-test")
  given Materializer = Materializer.matFromSystem

  /**
   * Constant-zero distribution: every sample returns 0. Useful for "lag is always 0" tests.
   */
  private def zeroLagDistribution: ContinuousDistribution =
    new ContinuousDistribution:
      override def density(x: Double): Double = if x == 0.0 then Double.PositiveInfinity else 0.0
      override def probability(x0: Double, x1: Double): Double = if x0 <= 0.0 && x1 >= 0.0 then 1.0 else 0.0
      override def cumulativeProbability(x: Double): Double = if x >= 0.0 then 1.0 else 0.0
      override def inverseCumulativeProbability(p: Double): Double = 0.0
      override def getMean: Double = 0.0
      override def getVariance: Double = 0.0
      override def getSupportLowerBound: Double = 0.0
      override def getSupportUpperBound: Double = 0.0
      override def createSampler(rng: org.apache.commons.rng.UniformRandomProvider): ContinuousDistribution.Sampler =
        () => 0.0

  /**
   * Constant-N distribution: every sample returns the configured value.
   */
  private def fixedLagDistribution(value: Double): ContinuousDistribution =
    new ContinuousDistribution:
      override def density(x: Double): Double = if x == value then Double.PositiveInfinity else 0.0
      override def probability(x0: Double, x1: Double): Double = if x0 <= value && x1 >= value then 1.0 else 0.0
      override def cumulativeProbability(x: Double): Double = if x >= value then 1.0 else 0.0
      override def inverseCumulativeProbability(p: Double): Double = value
      override def getMean: Double = value
      override def getVariance: Double = 0.0
      override def getSupportLowerBound: Double = value
      override def getSupportUpperBound: Double = value
      override def createSampler(rng: org.apache.commons.rng.UniformRandomProvider): ContinuousDistribution.Sampler =
        () => value

  private val seededRng = RandomSource.XO_RO_SHI_RO_128_PP.create(42L)

  private case class TestPutItemSample(
                                        override val writtenItemBytes: Long,
                                        override val previousItemBytes: Option[Long] = None,
                                        override val logicalPartitionAccess: LogicalPartitionAccess = SingleLogicalPartitionKey("k")
                                      ) extends PutItemSample

  private def admittedPut(eventTime: SimTime, bytes: Long, region: String): AdmittedPutItemSample =
    AdmittedPutItemSample(
      req = PutItemRequest(eventTime = eventTime, usecase = "test-put", itemBytes = bytes),
      executionTarget = DynamoDbTarget.Table(s"table-$region"),
      admissionTarget = DynamoDbTarget.Table(s"table-$region"),
      sample = TestPutItemSample(bytes),
      throughputDemand = BigDecimal(1),
      resolvedPartitionFootprint = ResolvedPartitionFootprint(
        totalPartitionCount = 1,
        partitionDemandById = SortedMap(0 -> BigDecimal(1))
      ),
      indexMaintenancePlan = Vector.empty
    )

  private def runCoordinator(
                              regions: Seq[String],
                              model: ReplicationModel,
                              elements: Seq[TimedElement[ReplicationCoordinator.OriginTaggedReplicationEvent]]
                            ): Seq[TimedEvent] =
    val flow = ReplicationCoordinator.flowOf(regions, model)
    val future: Future[Seq[TimedEvent]] =
      Source(elements.toVector).via(flow).runWith(Sink.seq[TimedEvent])
    Await.result(future, 3.seconds)

  "ReplicationCoordinator" should {

    "fan a write from one region out to all peers with zero-lag distribution" in {
      val regions = Seq("us-east-1", "eu-west-1", "ap-southeast-2")
      val model = ReplicationModel(
        defaultLagDistribution = Some(zeroLagDistribution),
        rng = seededRng
      )
      val sample = admittedPut(SimTime.of(1L), 1024L, "us-east-1")
      val results = runCoordinator(
        regions,
        model,
        Seq(
          TimedControlEvent.Tick(SimTime.of(1L)),
          ReplicationCoordinator.OriginTaggedReplicationEvent("us-east-1", sample)
        )
      )

      val replicatedWrites = results.collect {
        case w: ReplicationCoordinator.ReplicatedWriteForRegion => w
      }
      replicatedWrites.map(_.destinationRegion).toSet shouldBe Set("eu-west-1", "ap-southeast-2")
      replicatedWrites should have size 2

      val transferEvents = results.collect {
        case t: ReplicationCoordinator.TransferEventOutput => t.event
      }
      transferEvents should have size 2
      transferEvents.map(_.sourceRegion).toSet shouldBe Set("us-east-1")
      transferEvents.map(_.destinationRegion).toSet shouldBe Set("eu-west-1", "ap-southeast-2")
      transferEvents.foreach { e =>
        e.sourceService shouldBe "DynamoDB"
        e.bytes shouldBe 1024L
      }
    }

    "delay replicated writes by the sampled lag and emit them at the apply tick" in {
      val regions = Seq("a", "b")
      val model = ReplicationModel(
        defaultLagDistribution = Some(fixedLagDistribution(3.0)),
        rng = seededRng
      )
      val sample = admittedPut(SimTime.of(1L), 100L, "a")
      val results = runCoordinator(
        regions,
        model,
        Seq(
          TimedControlEvent.Tick(SimTime.of(1L)),
          ReplicationCoordinator.OriginTaggedReplicationEvent("a", sample),
          TimedControlEvent.Tick(SimTime.of(2L)),
          TimedControlEvent.Tick(SimTime.of(3L)),
          TimedControlEvent.Tick(SimTime.of(4L)),
          TimedControlEvent.Tick(SimTime.of(5L))
        )
      )

      val replicatedAtTick4OrEarlier = results.iterator.takeWhile {
        case t: TimedControlEvent.Tick if t.eventTime == SimTime.of(4L) => false
        case _ => true
      }.toVector
      replicatedAtTick4OrEarlier.collect { case _: ReplicationCoordinator.ReplicatedWriteForRegion => 1 } shouldBe empty

      val replicatedWrites = results.collect {
        case w: ReplicationCoordinator.ReplicatedWriteForRegion => w
      }
      replicatedWrites should have size 1
      replicatedWrites.head.destinationRegion shouldBe "b"
    }

    "drop reads — only writes replicate" in {
      val regions = Seq("a", "b")
      val model = ReplicationModel(
        defaultLagDistribution = Some(zeroLagDistribution),
        rng = seededRng
      )
      val getSample = AdmittedGetItemSample(
        req = GetItemRequest(eventTime = SimTime.of(1L), usecase = "test-get"),
        executionTarget = DynamoDbTarget.Table("orders"),
        admissionTarget = DynamoDbTarget.Table("orders"),
        readConsistency = ReadConsistency.EventuallyConsistent,
        sample = GetItemSample(itemBytes = Some(512L)),
        throughputDemand = BigDecimal(1),
        resolvedPartitionFootprint = ResolvedPartitionFootprint(
          totalPartitionCount = 1,
          partitionDemandById = SortedMap(0 -> BigDecimal(1))
        )
      )
      val results = runCoordinator(
        regions,
        model,
        Seq(
          TimedControlEvent.Tick(SimTime.of(1L)),
          ReplicationCoordinator.OriginTaggedReplicationEvent("a", getSample)
        )
      )

      results.collect { case _: ReplicationCoordinator.ReplicatedWriteForRegion => 1 } shouldBe empty
      results.collect { case _: ReplicationCoordinator.TransferEventOutput => 1 } shouldBe empty
    }

    "be deterministic for the same RNG seed" in {
      val regions = Seq("a", "b")
      def runOnce(): Seq[TimedEvent] =
        val rng = RandomSource.XO_RO_SHI_RO_128_PP.create(7L)
        val model = ReplicationModel(
          defaultLagDistribution = Some(LogNormalDistribution.of(0.0, 0.5)),
          rng = rng
        )
        val sample = admittedPut(SimTime.of(1L), 256L, "a")
        runCoordinator(
          regions,
          model,
          (1 to 30).flatMap { tick =>
            val tickEvent: TimedElement[ReplicationCoordinator.OriginTaggedReplicationEvent] =
              TimedControlEvent.Tick(SimTime.of(tick.toLong))
            if tick <= 5 then
              Seq(
                tickEvent,
                ReplicationCoordinator.OriginTaggedReplicationEvent("a", admittedPut(SimTime.of(tick.toLong), 256L, "a"))
              )
            else Seq(tickEvent)
          }
        )
      runOnce() shouldBe runOnce()
    }

    "emit a single Tick downstream per input Tick (deduplication via merger upstream is assumed)" in {
      val regions = Seq("a", "b")
      val model = ReplicationModel(
        defaultLagDistribution = Some(zeroLagDistribution),
        rng = seededRng
      )
      val results = runCoordinator(
        regions,
        model,
        Seq(
          TimedControlEvent.Tick(SimTime.of(1L)),
          TimedControlEvent.Tick(SimTime.of(2L)),
          TimedControlEvent.Tick(SimTime.of(3L))
        )
      )
      results.collect { case t: TimedControlEvent.Tick => t.eventTime.ticks } shouldBe Seq(1L, 2L, 3L)
    }

    "use the per-link distribution when configured, falling back to default otherwise" in {
      val regions = Seq("a", "b", "c")
      val model = ReplicationModel(
        perLinkLagDistribution = Map(
          ("a", "b") -> fixedLagDistribution(0.0),    // a→b: zero lag
          ("a", "c") -> fixedLagDistribution(2.0)     // a→c: 2-tick lag
        ),
        defaultLagDistribution = Some(zeroLagDistribution),
        rng = seededRng
      )
      val sample = admittedPut(SimTime.of(1L), 100L, "a")
      val results = runCoordinator(
        regions,
        model,
        Seq(
          TimedControlEvent.Tick(SimTime.of(1L)),
          ReplicationCoordinator.OriginTaggedReplicationEvent("a", sample),
          TimedControlEvent.Tick(SimTime.of(2L)),
          TimedControlEvent.Tick(SimTime.of(3L)),
          TimedControlEvent.Tick(SimTime.of(4L))
        )
      )

      // a→b emitted at tick 1 (zero lag); a→c emitted at tick 3 (lag=2 from origin tick 1).
      val byTickAndDest: Vector[(Long, String)] = {
        var seenTick = 0L
        val builder = Vector.newBuilder[(Long, String)]
        results.foreach {
          case t: TimedControlEvent.Tick => seenTick = t.eventTime.ticks
          case w: ReplicationCoordinator.ReplicatedWriteForRegion => builder += ((seenTick, w.destinationRegion))
          case _ =>
        }
        builder.result()
      }
      byTickAndDest should contain(1L -> "b")
      byTickAndDest should contain(3L -> "c")
    }

    "throw at link resolution when neither per-link nor default distribution is configured" in {
      val model = ReplicationModel(rng = seededRng) // no distributions at all
      val regions = Seq("a", "b")
      an[IllegalArgumentException] should be thrownBy {
        runCoordinator(
          regions,
          model,
          Seq(
            TimedControlEvent.Tick(SimTime.of(1L)),
            ReplicationCoordinator.OriginTaggedReplicationEvent("a", admittedPut(SimTime.of(1L), 100L, "a"))
          )
        )
      }
    }

    "produce no replication output for a single-region 'global' configuration" in {
      val regions = Seq("solo")
      val model = ReplicationModel(
        defaultLagDistribution = Some(zeroLagDistribution),
        rng = seededRng
      )
      val sample = admittedPut(SimTime.of(1L), 100L, "solo")
      val results = runCoordinator(
        regions,
        model,
        Seq(
          TimedControlEvent.Tick(SimTime.of(1L)),
          ReplicationCoordinator.OriginTaggedReplicationEvent("solo", sample)
        )
      )

      results.collect { case _: ReplicationCoordinator.ReplicatedWriteForRegion => 1 } shouldBe empty
      results.collect { case _: ReplicationCoordinator.TransferEventOutput => 1 } shouldBe empty
    }
  }
