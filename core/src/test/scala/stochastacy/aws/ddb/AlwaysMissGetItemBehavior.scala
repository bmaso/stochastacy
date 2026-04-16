package stochastacy.aws.ddb

import stochastacy.graphs.SimTime

object AlwaysMissGetItemBehavior extends UseCaseSampler[TableState]:
  override def getItem(request: GetItemRequest, state: TableState): Option[GetItemSample] = None
