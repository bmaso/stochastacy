package stochastacy.aws.dynamodb.table

final class FixedTableState(itemCount: Long, totalItemBytes: Long)
    extends SummaryTableState(itemCount, totalItemBytes)

object FixedTableState:
  def apply(itemCount: Long, totalItemBytes: Long): FixedTableState =
    new FixedTableState(itemCount, totalItemBytes)
