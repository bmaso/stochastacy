package stochastacy.workload

private[workload] case class RawEntry(
  include:      Vector[String],
  flows:        Vector[TemplateFlow],
  derivedFlows: Vector[WorkloadFlow] = Vector.empty
)

final class WorkloadFile private[workload] (
  private val entries: Map[String, RawEntry]
):
  def resolve(name: String): WorkloadTemplate =
    if !entries.contains(name) then
      throw WorkloadDslException(s"Unknown workload: '$name'")
    val (flows, bindings, derived) = collectFlows(name, Vector.empty)
    new WorkloadTemplate(flows, bindings, derived)

  private def collectFlows(
    name:    String,
    visited: Vector[String]
  ): (Vector[TemplateFlow], Set[String], Vector[WorkloadFlow]) =
    if visited.contains(name) then
      throw WorkloadDslException(
        s"Circular include: ${(visited :+ name).mkString(" -> ")}"
      )
    val entry      = entries.getOrElse(name,
      throw WorkloadDslException(s"Unknown workload: '$name'"))
    val newVisited = visited :+ name

    val (incFlows, incBindings, incDerived) = entry.include.foldLeft(
      (Vector.empty[TemplateFlow], Set.empty[String], Vector.empty[WorkloadFlow])
    ) { case ((flows, bindings, derived), incName) =>
      val (f, b, d) = collectFlows(incName, newVisited)
      (flows ++ f, bindings ++ b, derived ++ d)
    }

    val ownBindings = entry.flows.flatMap(indexVarsOf).toSet
    (incFlows ++ entry.flows, incBindings ++ ownBindings, incDerived ++ entry.derivedFlows)

  private def indexVarsOf(flow: TemplateFlow): Set[String] =
    flow.shape match
      case TemplateShape.Query(UnresolvedTarget.IndexVariable(n), _) => Set(n)
      case TemplateShape.Scan(UnresolvedTarget.IndexVariable(n), _)  => Set(n)
      case _                                                          => Set.empty
