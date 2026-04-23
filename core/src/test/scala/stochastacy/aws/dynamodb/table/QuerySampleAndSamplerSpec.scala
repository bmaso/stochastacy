package stochastacy.aws.dynamodb.table

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.{DynamoDbReadTarget, QueryRequest, ScanRequest}
import stochastacy.sim.SimTime

class QuerySampleAndSamplerSpec extends AnyWordSpec with should.Matchers:

  import LogicalPartitionAccess.*

  "QuerySample" should {
    "accept valid evaluated and returned summary values" in {
      val sample = QuerySample(
        evaluatedItemCount = 10L,
        evaluatedBytes = 4096L,
        returnedItemCount = 3L,
        returnedBytes = 1024L,
        logicalPartitionAccess = MultipleLogicalPartitionKeys(Vector("k1", "k2"))
      )

      sample.evaluatedItemCount shouldBe 10L
      sample.evaluatedBytes shouldBe 4096L
      sample.returnedItemCount shouldBe 3L
      sample.returnedBytes shouldBe 1024L
    }

    "reject invalid evaluated and returned relationships" in {
      val itemCountError = the[IllegalArgumentException] thrownBy {
        QuerySample(
          evaluatedItemCount = 1L,
          evaluatedBytes = 100L,
          returnedItemCount = 2L,
          returnedBytes = 50L,
          logicalPartitionAccess = SingleLogicalPartitionKey("k1")
        )
      }
      itemCountError.getMessage should include("returnedItemCount")

      val returnedBytesError = the[IllegalArgumentException] thrownBy {
        QuerySample(
          evaluatedItemCount = 2L,
          evaluatedBytes = 100L,
          returnedItemCount = 1L,
          returnedBytes = 200L,
          logicalPartitionAccess = SingleLogicalPartitionKey("k1")
        )
      }
      returnedBytesError.getMessage should include("returnedBytes")
    }

    "reject all-partitions logical access" in {
      val error = the[IllegalArgumentException] thrownBy {
        QuerySample(
          evaluatedItemCount = 1L,
          evaluatedBytes = 100L,
          returnedItemCount = 1L,
          returnedBytes = 100L,
          logicalPartitionAccess = AllPartitions
        )
      }

      error.getMessage should include("QuerySample requires")
    }
  }

  "ScanSample" should {
    "accept valid evaluated and returned summary values" in {
      val sample = ScanSample(
        evaluatedItemCount = 12L,
        evaluatedBytes = 12288L,
        returnedItemCount = 4L,
        returnedBytes = 2048L,
        logicalPartitionAccess = AllPartitions
      )

      sample.evaluatedItemCount shouldBe 12L
      sample.evaluatedBytes shouldBe 12288L
      sample.returnedItemCount shouldBe 4L
      sample.returnedBytes shouldBe 2048L
    }

    "reject invalid evaluated and returned relationships" in {
      val itemCountError = the[IllegalArgumentException] thrownBy {
        ScanSample(
          evaluatedItemCount = 1L,
          evaluatedBytes = 100L,
          returnedItemCount = 2L,
          returnedBytes = 50L,
          logicalPartitionAccess = AllPartitions
        )
      }
      itemCountError.getMessage should include("returnedItemCount")

      val returnedBytesError = the[IllegalArgumentException] thrownBy {
        ScanSample(
          evaluatedItemCount = 2L,
          evaluatedBytes = 100L,
          returnedItemCount = 1L,
          returnedBytes = 200L,
          logicalPartitionAccess = AllPartitions
        )
      }
      returnedBytesError.getMessage should include("returnedBytes")
    }

    "reject single-partition logical access" in {
      val error = the[IllegalArgumentException] thrownBy {
        ScanSample(
          evaluatedItemCount = 1L,
          evaluatedBytes = 100L,
          returnedItemCount = 1L,
          returnedBytes = 100L,
          logicalPartitionAccess = SingleLogicalPartitionKey("k1")
        )
      }

      error.getMessage should include("ScanSample requires")
    }
  }

  "UseCaseSampler" should {
    "reject query use-cases by default when query behavior is not implemented" in {
      val sampler = new UseCaseSampler[TableState] {}
      val request = QueryRequest(
        eventTime = SimTime.of(1L),
        usecase = "unsupported-query",
        target = DynamoDbReadTarget.Table("orders")
      )

      val error = the[UnsupportedOperationException] thrownBy {
        sampler.query(request, FixedTableState(itemCount = 0L, totalItemBytes = 0L))
      }

      error.getMessage should include("Query is not supported for use-case 'unsupported-query'")
    }

    "reject scan use-cases by default when scan behavior is not implemented" in {
      val sampler = new UseCaseSampler[TableState] {}
      val request = ScanRequest(
        eventTime = SimTime.of(1L),
        usecase = "unsupported-scan",
        target = DynamoDbReadTarget.Table("orders")
      )

      val error = the[UnsupportedOperationException] thrownBy {
        sampler.scan(request, FixedTableState(itemCount = 0L, totalItemBytes = 0L))
      }

      error.getMessage should include("Scan is not supported for use-case 'unsupported-scan'")
    }
  }
