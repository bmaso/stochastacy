package stochastacy.aws.dynamodb.table

trait TableState:
  def itemCount: Long
  def totalItemBytes: Long
