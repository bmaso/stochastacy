package stochastacy.aws.ddb

case class FixedTableState(override val itemCount: Long, override val totalItemBytes: Long) extends TableState
