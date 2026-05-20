package stochastacy.workload

import org.apache.commons.rng.simple.RandomSource

case class ShapeMetadata(index: Int, requestType: String)
case class ShapeSample(tick: Long, shapeIndex: Int, count: Int)
case class EvaluationResult(shapes: Vector[ShapeMetadata], samples: Vector[ShapeSample])

object WorkloadEvaluator:

  def evaluate(
    yaml:         String,
    workloadName: String,
    tickCount:    Long,
    seed:         Long
  ): EvaluationResult =
    val template = WorkloadDsl.parse(yaml).resolve(workloadName)
    val rng      = RandomSource.KISS.create(seed)
    val shapes   = template.flows.zipWithIndex.map { (flow, i) =>
      ShapeMetadata(i, requestTypeName(flow.shape))
    }
    val samples =
      for
        tick      <- (1L to tickCount).toVector
        (flow, i) <- template.flows.zipWithIndex
      yield
        val count = flow.rate.sample(tick, rng, ())._1
        ShapeSample(tick, i, count)
    EvaluationResult(shapes, samples)

  private def requestTypeName(shape: TemplateShape): String = shape match
    case TemplateShape.GetItem              => "get-item"
    case TemplateShape.DeleteItem           => "delete-item"
    case TemplateShape.PutItem(_)           => "put-item"
    case TemplateShape.UpdateItem(_)        => "update-item"
    case TemplateShape.Query(_, _)          => "query"
    case TemplateShape.Scan(_, _)           => "scan"
    case TemplateShape.TransactWriteItems(_) => "transact-write-items"
    case TemplateShape.TransactGetItems(_)  => "transact-get-items"
