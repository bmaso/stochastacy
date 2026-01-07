package stochastacy.aws.ddb

import stochastacy.graphs.SimTime

object AlwaysMissGetItemBehavior extends UseCaseSampler[TableState]:
  override def getItem(request: GetItemRequest, state: TableState): Option[GetItemSample] = {
    val sample = new GetItemSample:
      override val getItemBytes: Option[Long] = None
    Some(sample)
  }