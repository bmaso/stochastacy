package stochastacy.aws.ddb

trait TableState:
  def itemCount: Long
  def totalItemBytes: Long
