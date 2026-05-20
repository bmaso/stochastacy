package stochastacy.workload

import org.yaml.snakeyaml.Yaml
import stochastacy.aws.dynamodb.table.ReadConsistency

import java.util.{List => JList, Map => JMap}
import scala.jdk.CollectionConverters.*

object WorkloadDsl:

  def parse(yamlString: String): WorkloadFile =
    val raw: Any =
      try new Yaml().load(yamlString)
      catch case e: Exception =>
        throw WorkloadDslException(s"YAML parse error: ${e.getMessage}", e)
    val top = requireMap(raw, "top-level document")
    val wRaw       = top.get("workloads") match
      case null => throw WorkloadDslException("Missing required 'workloads' key")
      case v    => requireMap(v, "'workloads' value")
    val entries = wRaw.asScala.map { (k, v) => k -> parseEntry(k, v) }.toMap
    new WorkloadFile(entries)

  private def parseEntry(name: String, v: Any): RawEntry =
    if v == null then return RawEntry(Vector.empty, Vector.empty)
    val m        = requireMap(v, s"workload '$name'")
    val includes = m.get("include") match
      case null => Vector.empty[String]
      case v    => requireList(v, s"workload '$name' include").asScala.map(_.toString).toVector
    val flows = m.get("flows") match
      case null => Vector.empty[TemplateFlow]
      case v    =>
        requireList(v, s"workload '$name' flows").asScala.map { item =>
          parseFlow(requireMap(item, s"flow in workload '$name'"))
        }.toVector
    RawEntry(includes, flows)

  private def parseFlow(m: JMap[String, Any]): TemplateFlow =
    val flowType = m.get("type") match
      case null => throw WorkloadDslException("Flow missing required 'type' field")
      case v    => v.toString
    val rate = m.get("rate") match
      case null => throw WorkloadDslException(s"Flow '$flowType' missing required 'rate' field")
      case v    => parseRateSampler(v, s"flow '$flowType' rate")
    val shape = flowType match
      case "get-item"             => TemplateShape.GetItem
      case "delete-item"          => TemplateShape.DeleteItem
      case "put-item"             => TemplateShape.PutItem(parseItemBytes(m, flowType))
      case "update-item"          => TemplateShape.UpdateItem(parseItemBytes(m, flowType))
      case "query"                => parseQueryOrScan(m, isQuery = true)
      case "scan"                 => parseQueryOrScan(m, isQuery = false)
      case "transact-write-items" => parseTransactWrite(m)
      case "transact-get-items"   => parseTransactGet(m)
      case other                  => throw WorkloadDslException(s"Unknown flow type: '$other'")
    TemplateFlow(rate, shape)

  private def parseItemBytes(m: JMap[String, Any], flowType: String): StatelessSampler[Long] =
    m.get("item-bytes") match
      case null => throw WorkloadDslException(s"Flow '$flowType' missing required 'item-bytes' field")
      case v    => parseByteSampler(v, "item-bytes")

  private def parseQueryOrScan(m: JMap[String, Any], isQuery: Boolean): TemplateShape =
    val target = m.get("target") match
      case null => UnresolvedTarget.DefaultTable
      case v =>
        val tm  = requireMap(v, "target")
        tm.get("index") match
          case null => throw WorkloadDslException("target record must have an 'index' field")
          case idx =>
            val raw = idx.toString
            if !raw.startsWith("$") then
              throw WorkloadDslException(
                s"target.index must be a variable reference (starting with '$$'), got: '$raw'"
              )
            UnresolvedTarget.IndexVariable(raw.drop(1))
    val rc = m.get("read-consistency") match
      case null => ReadConsistency.EventuallyConsistent
      case v => v.toString match
        case "eventually-consistent" => ReadConsistency.EventuallyConsistent
        case "strongly-consistent"   => ReadConsistency.StronglyConsistent
        case other => throw WorkloadDslException(
          s"Unknown read-consistency value: '$other'. Expected 'eventually-consistent' or 'strongly-consistent'."
        )
    if isQuery then TemplateShape.Query(target, rc)
    else TemplateShape.Scan(target, rc)

  private def parseTransactWrite(m: JMap[String, Any]): TemplateShape =
    val perItemBytes = m.get("per-item-bytes") match
      case null => throw WorkloadDslException(
        "transact-write-items missing required 'per-item-bytes' field"
      )
      case v =>
        requireList(v, "per-item-bytes").asScala
          .map(item => parseByteSampler(item, "per-item-bytes entry"))
          .toVector
    if perItemBytes.isEmpty then
      throw WorkloadDslException("transact-write-items 'per-item-bytes' must not be empty")
    TemplateShape.TransactWriteItems(perItemBytes)

  private def parseTransactGet(m: JMap[String, Any]): TemplateShape =
    val itemCount = m.get("item-count") match
      case null => throw WorkloadDslException(
        "transact-get-items missing required 'item-count' field"
      )
      case v => parseRateSampler(v, "item-count")
    TemplateShape.TransactGetItems(itemCount)

  // ── Rate sampler ────────────────────────────────────────────────────────────

  private def parseRateSampler(v: Any, ctx: String): StatelessSampler[Int] =
    v match
      case n: java.lang.Integer => PoissonSampler.constant(n.doubleValue())
      case n: java.lang.Long    => PoissonSampler.constant(n.doubleValue())
      case m: JMap[_, _] =>
        val sm   = m.asInstanceOf[JMap[String, Any]]
        val dist = sm.get("distribution") match
          case null => throw WorkloadDslException(s"$ctx: missing required 'distribution' field")
          case d    => d.toString
        dist match
          case "poisson" =>
            val lambda = sm.get("lambda") match
              case null => throw WorkloadDslException(s"$ctx poisson: missing required 'lambda' field")
              case v    => parseValueExpr(v, s"$ctx poisson lambda")
            PoissonSampler(lambda)
          case "binomial" =>
            val n = sm.get("n") match
              case null => throw WorkloadDslException(s"$ctx binomial: missing required 'n' field")
              case v    => toPositiveInt(v, s"$ctx binomial n")
            val p = sm.get("p") match
              case null => throw WorkloadDslException(s"$ctx binomial: missing required 'p' field")
              case v    => parseValueExpr(v, s"$ctx binomial p")
            BinomialSampler(_ => n, p)
          case "constant" =>
            val value = sm.get("value") match
              case null => throw WorkloadDslException(s"$ctx constant: missing required 'value' field")
              case v    => toNonNegativeInt(v, s"$ctx constant value")
            ConstantSampler(value)
          case other => throw WorkloadDslException(s"$ctx: unknown distribution '$other'")
      case other => throw WorkloadDslException(
        s"$ctx: invalid rate sampler (expected integer or mapping, got ${other.getClass.getSimpleName})"
      )

  // ── Byte sampler ────────────────────────────────────────────────────────────

  private def parseByteSampler(v: Any, ctx: String): StatelessSampler[Long] =
    v match
      case n: java.lang.Integer => ConstantSampler(n.longValue())
      case n: java.lang.Long    => ConstantSampler(n)
      case m: JMap[_, _] =>
        val sm   = m.asInstanceOf[JMap[String, Any]]
        val dist = sm.get("distribution") match
          case null => throw WorkloadDslException(s"$ctx: missing required 'distribution' field")
          case d    => d.toString
        dist match
          case "log-normal" =>
            val mu    = sm.get("mu") match
              case null => throw WorkloadDslException(s"$ctx log-normal: missing 'mu' field")
              case v    => parseValueExpr(v, s"$ctx log-normal mu")
            val sigma = sm.get("sigma") match
              case null => throw WorkloadDslException(s"$ctx log-normal: missing 'sigma' field")
              case v    => parseValueExpr(v, s"$ctx log-normal sigma")
            MappedSampler(LogNormalSampler(mu, sigma), t => t, (_, d) => d.toLong)
          case "normal" =>
            val mean   = sm.get("mean") match
              case null => throw WorkloadDslException(s"$ctx normal: missing 'mean' field")
              case v    => parseValueExpr(v, s"$ctx normal mean")
            val stddev = sm.get("stddev") match
              case null => throw WorkloadDslException(s"$ctx normal: missing 'stddev' field")
              case v    => parseValueExpr(v, s"$ctx normal stddev")
            MappedSampler(NormalSampler(mean, stddev), t => t, (_, d) => d.toLong)
          case "uniform" =>
            val min = sm.get("min") match
              case null => throw WorkloadDslException(s"$ctx uniform: missing 'min' field")
              case v    => parseValueExpr(v, s"$ctx uniform min")
            val max = sm.get("max") match
              case null => throw WorkloadDslException(s"$ctx uniform: missing 'max' field")
              case v    => parseValueExpr(v, s"$ctx uniform max")
            MappedSampler(UniformSampler(min, max), t => t, (_, d) => d.toLong)
          case "constant" =>
            val value = sm.get("value") match
              case null => throw WorkloadDslException(s"$ctx constant: missing 'value' field")
              case v    => toPositiveLong(v, s"$ctx constant value")
            ConstantSampler(value)
          case other => throw WorkloadDslException(s"$ctx: unknown distribution '$other'")
      case other => throw WorkloadDslException(
        s"$ctx: invalid byte sampler (expected integer or mapping, got ${other.getClass.getSimpleName})"
      )

  // ── Value expression ────────────────────────────────────────────────────────

  private def parseValueExpr(v: Any, ctx: String): Long => Double =
    v match
      case n: java.lang.Integer => _ => n.doubleValue()
      case n: java.lang.Long    => _ => n.doubleValue()
      case n: java.lang.Double  => _ => n.doubleValue()
      case n: java.lang.Float   => _ => n.doubleValue()
      case m: JMap[_, _] =>
        val sm    = m.asInstanceOf[JMap[String, Any]]
        val shape = sm.get("shape") match
          case null => throw WorkloadDslException(s"$ctx: value-expr map missing 'shape' field")
          case s    => s.toString
        shape match
          case "sinusoid" =>
            val min         = toDouble(sm.get("min"),         s"$ctx sinusoid min")
            val max         = toDouble(sm.get("max"),         s"$ctx sinusoid max")
            val periodTicks = toLong(sm.get("period-ticks"),  s"$ctx sinusoid period-ticks")
            val peakTick    = toLong(sm.get("peak-tick"),     s"$ctx sinusoid peak-tick")
            TemporalShapeFunctions.sinusoid(min, max, periodTicks, peakTick)
          case "linear-factor" =>
            val rate = toDouble(sm.get("rate"), s"$ctx linear-factor rate")
            TemporalShapeFunctions.linearFactor(rate)
          case "triangular-factor" =>
            val start      = toLong(sm.get("start-tick"),   s"$ctx triangular-factor start-tick")
            val end        = toLong(sm.get("end-tick"),     s"$ctx triangular-factor end-tick")
            val multiplier = toDouble(sm.get("multiplier"), s"$ctx triangular-factor multiplier")
            TemporalShapeFunctions.triangularFactor(start, end, multiplier)
          case "weekdays" =>
            val ticksPerDay = toLong(sm.get("ticks-per-day"), s"$ctx weekdays ticks-per-day")
            tick => if TemporalShapeFunctions.weekdays(ticksPerDay)(tick) then 1.0 else 0.0
          case "time-window" =>
            val start = toLong(sm.get("start-tick"), s"$ctx time-window start-tick")
            val end   = toLong(sm.get("end-tick"),   s"$ctx time-window end-tick")
            val inner = sm.get("inner") match
              case null => throw WorkloadDslException(s"$ctx time-window: missing 'inner' field")
              case v    => parseValueExpr(v, s"$ctx time-window inner")
            tick => if tick >= start && tick <= end then inner(tick) else 0.0
          case other => throw WorkloadDslException(s"$ctx: unknown value-expr shape '$other'")
      case other => throw WorkloadDslException(
        s"$ctx: invalid value-expr (expected number or mapping, got ${other.getClass.getSimpleName})"
      )

  // ── Primitive helpers ───────────────────────────────────────────────────────

  private def requireMap(v: Any, ctx: String): JMap[String, Any] =
    v match
      case m: JMap[_, _] => m.asInstanceOf[JMap[String, Any]]
      case _ => throw WorkloadDslException(
        s"Expected a YAML mapping in $ctx, got: ${if v == null then "null" else v.getClass.getSimpleName}"
      )

  private def requireList(v: Any, ctx: String): JList[Any] =
    v match
      case l: JList[_] => l.asInstanceOf[JList[Any]]
      case _ => throw WorkloadDslException(
        s"Expected a YAML list in $ctx, got: ${if v == null then "null" else v.getClass.getSimpleName}"
      )

  private def toDouble(v: Any, ctx: String): Double =
    v match
      case n: java.lang.Number => n.doubleValue()
      case null => throw WorkloadDslException(s"Missing required field: $ctx")
      case _    => throw WorkloadDslException(s"Expected a number for $ctx, got: ${v.getClass.getSimpleName}")

  private def toLong(v: Any, ctx: String): Long =
    v match
      case n: java.lang.Integer => n.longValue()
      case n: java.lang.Long    => n.longValue()
      case null => throw WorkloadDslException(s"Missing required field: $ctx")
      case _    => throw WorkloadDslException(s"Expected an integer for $ctx, got: ${v.getClass.getSimpleName}")

  private def toPositiveInt(v: Any, ctx: String): Int =
    val n = toLong(v, ctx)
    if n <= 0 then throw WorkloadDslException(s"$ctx must be a positive integer, got: $n")
    if n > Int.MaxValue then throw WorkloadDslException(s"$ctx exceeds Int range: $n")
    n.toInt

  private def toNonNegativeInt(v: Any, ctx: String): Int =
    val n = toLong(v, ctx)
    if n < 0 then throw WorkloadDslException(s"$ctx must be non-negative, got: $n")
    if n > Int.MaxValue then throw WorkloadDslException(s"$ctx exceeds Int range: $n")
    n.toInt

  private def toPositiveLong(v: Any, ctx: String): Long =
    val n = toLong(v, ctx)
    if n <= 0 then throw WorkloadDslException(s"$ctx must be a positive integer, got: $n")
    n
