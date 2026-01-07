package stochastacy.aws.ddb

sealed trait DynamoDBError

case object ProvisionedThroughputExceeded extends DynamoDBError
case object Throttled extends DynamoDBError
