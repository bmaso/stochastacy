package stochastacy.aws.dynamodb.table

case class FixedTableState(override val itemCount: Long, override val totalItemBytes: Long) extends TableState
