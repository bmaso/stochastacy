package stochastacy.workload

import stochastacy.aws.dynamodb.DynamoDbReadTarget
import stochastacy.aws.dynamodb.table.ReadConsistency

private[workload] sealed trait UnresolvedTarget
private[workload] object UnresolvedTarget:
  case object DefaultTable                extends UnresolvedTarget
  case class  IndexVariable(name: String) extends UnresolvedTarget

private[workload] sealed trait TemplateShape
private[workload] object TemplateShape:
  case object GetItem                                                            extends TemplateShape
  case object DeleteItem                                                         extends TemplateShape
  case class  PutItem(itemBytes: StatelessSampler[Long])                        extends TemplateShape
  case class  UpdateItem(itemBytes: StatelessSampler[Long])                     extends TemplateShape
  case class  Query(target: UnresolvedTarget, rc: ReadConsistency)              extends TemplateShape
  case class  Scan(target: UnresolvedTarget, rc: ReadConsistency)               extends TemplateShape
  case class  TransactWriteItems(perItemBytes: Vector[StatelessSampler[Long]])  extends TemplateShape
  case class  TransactGetItems(itemCount: StatelessSampler[Int])                extends TemplateShape

private[workload] case class TemplateFlow(
  rate:  StatelessSampler[Int],
  shape: TemplateShape,
  id:    Option[String] = None
)

final class WorkloadTemplate private[workload] (
  val flows:            Vector[TemplateFlow],
  val requiredBindings: Set[String],
  val derivedFlows:     Vector[WorkloadFlow] = Vector.empty
):
  def bind(
    tableName: String,
    usecase:   String,
    indices:   Map[String, String] = Map.empty
  ): WorkloadDefinition =
    val missing = requiredBindings -- indices.keySet
    if missing.nonEmpty then
      throw WorkloadDslException(
        s"Missing index bindings: ${missing.toSeq.sorted.mkString(", ")}"
      )
    val boundIndependent = flows.zipWithIndex.map { (f, i) =>
      val id   = f.id.getOrElse(s"flow-$i")
      val shape = bindShape(f.shape, tableName, indices)
      WorkloadFlow.Independent(id, PacedRequestFactory(f.rate, shape))
    }
    WorkloadDefinition(tableName, usecase, boundIndependent ++ derivedFlows)

  private def bindShape(
    shape:     TemplateShape,
    tableName: String,
    indices:   Map[String, String]
  ): RequestShape =
    shape match
      case TemplateShape.GetItem                => RequestShape.GetItem
      case TemplateShape.DeleteItem             => RequestShape.DeleteItem
      case TemplateShape.PutItem(b)             => RequestShape.PutItem(b)
      case TemplateShape.UpdateItem(b)          => RequestShape.UpdateItem(b)
      case TemplateShape.TransactWriteItems(bs) => RequestShape.TransactWriteItems(bs)
      case TemplateShape.TransactGetItems(ic)   => RequestShape.TransactGetItems(ic)
      case TemplateShape.Query(target, rc)      =>
        RequestShape.Query(resolveTarget(target, tableName, indices), rc)
      case TemplateShape.Scan(target, rc)       =>
        RequestShape.Scan(resolveTarget(target, tableName, indices), rc)

  private def resolveTarget(
    target:    UnresolvedTarget,
    tableName: String,
    indices:   Map[String, String]
  ): DynamoDbReadTarget =
    target match
      case UnresolvedTarget.DefaultTable        => DynamoDbReadTarget.Table(tableName)
      case UnresolvedTarget.IndexVariable(name) =>
        DynamoDbReadTarget.GlobalSecondaryIndex(tableName, indices(name))
