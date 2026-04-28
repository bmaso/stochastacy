package stochastacy.aws.dynamodb.table

import stochastacy.aws.dynamodb.GetItemRequest

object AlwaysMissGetItemBehavior extends UseCaseSampler[TableState]:
  override def getItem(request: GetItemRequest, ctx: SamplerContext[TableState]): GetItemSample =
    GetItemSample(itemBytes = None)
