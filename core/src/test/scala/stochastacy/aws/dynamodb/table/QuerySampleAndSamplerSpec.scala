package stochastacy.aws.dynamodb.table

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.{DynamoDbReadTarget, QueryRequest, ScanRequest}
import stochastacy.sim.SimTime

class QuerySampleAndSamplerSpec extends AnyWordSpec with should.Matchers:

  "QuerySample" should {
    "accept valid evaluated and returned summary values" in {
      val sample = QuerySample(
        evaluatedItemCount = 10L,
        evaluatedBytes = 4096L,
        returnedItemCount = 3L,
        returnedBytes = 1024L
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
          returnedBytes = 50L
        )
      }
      itemCountError.getMessage should include("returnedItemCount")

      val returnedBytesError = the[IllegalArgumentException] thrownBy {
        QuerySample(
          evaluatedItemCount = 2L,
          evaluatedBytes = 100L,
          returnedItemCount = 1L,
          returnedBytes = 200L
        )
      }
      returnedBytesError.getMessage should include("returnedBytes")
    }
  }

  "ScanSample" should {
    "accept valid evaluated and returned summary values" in {
      val sample = ScanSample(
        evaluatedItemCount = 12L,
        evaluatedBytes = 12288L,
        returnedItemCount = 4L,
        returnedBytes = 2048L
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
          returnedBytes = 50L
        )
      }
      itemCountError.getMessage should include("returnedItemCount")

      val returnedBytesError = the[IllegalArgumentException] thrownBy {
        ScanSample(
          evaluatedItemCount = 2L,
          evaluatedBytes = 100L,
          returnedItemCount = 1L,
          returnedBytes = 200L
        )
      }
      returnedBytesError.getMessage should include("returnedBytes")
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
