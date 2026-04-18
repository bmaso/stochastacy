package stochastacy.aws.dynamodb.table

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.sim.SimTime

class DynamoDbConsumptionEventSpec extends AnyWordSpec with should.Matchers:

  "DynamoDbConsumptionEvent" should {
    "represent read capacity as a timed consumption fact" in {
      val evt = DynamoDbConsumptionEvent.ReadCapacityConsumed(
        eventTime = SimTime.of(42L),
        usecase = "demo-read",
        target = DynamoDbTarget.Table("orders"),
        units = BigDecimal(0.5),
        consistency = ReadConsistency.EventuallyConsistent
      )

      evt.eventTime shouldBe SimTime.of(42L)
      evt.usecase shouldBe "demo-read"
      evt.target shouldBe DynamoDbTarget.Table("orders")
      evt.units shouldBe BigDecimal(0.5)
      evt.consistency shouldBe ReadConsistency.EventuallyConsistent
    }

    "represent storage bytes read as a timed consumption fact" in {
      val evt = DynamoDbConsumptionEvent.StorageBytesRead(
        eventTime = SimTime.of(7L),
        usecase = "demo-read",
        target = DynamoDbTarget.GlobalSecondaryIndex("orders", "by-status"),
        bytes = 4096L
      )

      evt.eventTime shouldBe SimTime.of(7L)
      evt.usecase shouldBe "demo-read"
      evt.target shouldBe DynamoDbTarget.GlobalSecondaryIndex("orders", "by-status")
      evt.bytes shouldBe 4096L
    }

    "represent storage bytes deleted as a timed consumption fact" in {
      val evt = DynamoDbConsumptionEvent.StorageBytesDeleted(
        eventTime = SimTime.of(9L),
        usecase = "demo-delete",
        target = DynamoDbTarget.Table("orders"),
        bytes = 768L
      )

      evt.eventTime shouldBe SimTime.of(9L)
      evt.usecase shouldBe "demo-delete"
      evt.target shouldBe DynamoDbTarget.Table("orders")
      evt.bytes shouldBe 768L
    }
  }
