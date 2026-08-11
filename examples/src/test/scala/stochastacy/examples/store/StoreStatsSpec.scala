package stochastacy.examples.store

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class StoreStatsSpec extends AnyWordSpec with should.Matchers:

  "StoreStats.observations" should {
    "map RequestServiced to a latency observation" in {
      StoreStats.observations(RequestServiced(0.5)) shouldBe Seq("latency" -> 0.5)
    }
    "map WorkPerformed to item and byte observations" in {
      StoreStats.observations(WorkPerformed(3L, 2048L)) shouldBe Seq("work.items" -> 3.0, "work.bytes" -> 2048.0)
    }
    "map DataReturned to returned-item and returned-byte observations" in {
      StoreStats.observations(DataReturned(2L, 100L)) shouldBe Seq("returned.items" -> 2.0, "returned.bytes" -> 100.0)
    }
    "not observe StorageDelta (signed; covered by final state)" in {
      StoreStats.observations(StorageDelta(-1000L)) shouldBe empty
    }
  }
