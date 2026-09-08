package stochastacy.demo

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path

import scala.concurrent.{ExecutionContext, Future}

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer

import stochastacy.aws.examples.demo.{SingleTableMonteCarloRunner, SingleTableScenario, TrialResult as AwsTrialResult}

/**
 * The shared v2 demo → Postgres/Grafana bridge. It runs a v2 AWS demo's Monte Carlo runner, **adapts** each
 * trial's per-tick + summary metrics into the generic [[TrialResult]] model (the v2 metric strings already
 * match [[DemoMetric]] `exportName`), and drives them through the existing staging layer: [[DemoReportBuilder]]
 * produces every record family — including the **windowed** records the dashboards' time panels read, which the
 * v2 AWS exporter never emits — and [[DemoPostgresStaging]] loads them for Grafana.
 *
 * `generate → stage → view`: `generateSingleTable` writes the JSONL, [[DemoPostgresStaging.stage]] loads it,
 * and [[viewUrl]] points at the dashboard. Reused by every single-table v2 demo (order-tracking, thermostat
 * single/mixed-mode); multi-table demos add their own adapter over the same staging.
 */
object GrafanaBridge:

  /** Adapt one v2 single-table trial into a generic [[TrialResult]] — base RCU/WCU/storage/cumulative-cost per
   *  tick + per-GSI capacity, and the summary totals. Metric names come straight from [[DemoMetric.exportName]].
   *  For a **provisioned** table it additionally surfaces the per-tick reserved capacity, billing-mode
   *  indicator, and throttle count — the temporal signal a mixed-mode / reconfiguration story turns on (a
   *  summary total would hide *when* throttling starts). On-demand tables emit none of these, so their output
   *  is unchanged. */
  def adaptSingleTable(scenarioId: String, gsiNames: Vector[String], provisioned: Boolean, t: AwsTrialResult): TrialResult =
    val timeSeries = t.timeSeries.flatMap { p =>
      val base = Vector(
        SimulationTimeSeriesPoint(p.tick, DemoMetric.ReadCapacityUnits,       p.readCapacityUnits),
        SimulationTimeSeriesPoint(p.tick, DemoMetric.WriteCapacityUnits,      p.writeCapacityUnits),
        SimulationTimeSeriesPoint(p.tick, DemoMetric.StorageBytes,            BigDecimal(p.storageBytes)),
        SimulationTimeSeriesPoint(p.tick, DemoMetric.CumulativeEstimatedCost, p.cumulativeEstimatedCost)
      )
      val gsi = gsiNames.flatMap { n =>
        p.gsiReadCapacityUnits.get(n).map(v  => SimulationTimeSeriesPoint(p.tick, DemoMetric.GsiReadCapacityUnits(n), v)).toVector ++
        p.gsiWriteCapacityUnits.get(n).map(v => SimulationTimeSeriesPoint(p.tick, DemoMetric.GsiWriteCapacityUnits(n), v)).toVector
      }
      val provisionedSeries =
        if !provisioned then Vector.empty
        else
          Vector(
            SimulationTimeSeriesPoint(p.tick, DemoMetric.ThrottleCount,       BigDecimal(p.throttledRequests)),
            SimulationTimeSeriesPoint(p.tick, DemoMetric.BillingModeIndicator, if p.provisionedReadCapacityUnits.isDefined then BigDecimal(1) else BigDecimal(0))
          ) ++
          p.provisionedReadCapacityUnits.map(v  => SimulationTimeSeriesPoint(p.tick, DemoMetric.ProvisionedReadCapacityUnits, BigDecimal(v))).toVector ++
          p.provisionedWriteCapacityUnits.map(v => SimulationTimeSeriesPoint(p.tick, DemoMetric.ProvisionedWriteCapacityUnits, BigDecimal(v))).toVector
      base ++ gsi ++ provisionedSeries
    }
    val summary = Vector(
      TrialSummaryValue(DemoMetric.TotalReadCapacityUnits,  t.summary.totalReadCapacityUnits),
      TrialSummaryValue(DemoMetric.TotalWriteCapacityUnits, t.summary.totalWriteCapacityUnits),
      TrialSummaryValue(DemoMetric.TotalStorageByteTicks,   BigDecimal(t.summary.totalStorageByteTicks)),
      TrialSummaryValue(DemoMetric.FinalStorageBytes,       BigDecimal(t.summary.finalStorageBytes)),
      TrialSummaryValue(DemoMetric.TotalEstimatedCost,      t.summary.totalEstimatedCost)
    ) ++ gsiNames.flatMap { n =>
      t.summary.gsiTotalReadCapacityUnits.get(n).map(v  => TrialSummaryValue(DemoMetric.TotalGsiReadCapacityUnits(n), v)).toVector ++
      t.summary.gsiTotalWriteCapacityUnits.get(n).map(v => TrialSummaryValue(DemoMetric.TotalGsiWriteCapacityUnits(n), v)).toVector
    }
    TrialResult(scenarioId, t.trialId, timeSeries, summary)

  /** Shift a record's tick / window-start tick by `offsetSeconds`, so Grafana plots ticks as wall-clock time
   *  (a tick is one second). Summaries have no tick and pass through. */
  def applyTickOffset(record: DemoExportRecord, offsetSeconds: Long): DemoExportRecord = record match
    case r: DemoExportRecord.TrialTimeSeriesRecord           => r.copy(tick = r.tick + offsetSeconds)
    case r: DemoExportRecord.TrialWindowTimeSeriesRecord     => r.copy(windowStartTick = r.windowStartTick + offsetSeconds)
    case r: DemoExportRecord.AggregateTimeSeriesRecord       => r.copy(tick = r.tick + offsetSeconds)
    case r: DemoExportRecord.AggregateWindowTimeSeriesRecord => r.copy(windowStartTick = r.windowStartTick + offsetSeconds)
    case other                                               => other

  /** All six record families (trial/aggregate × time-series/window/summary) for the adapted trials, tick-shifted. */
  def recordsFor(scenarioId: String, gsiNames: Vector[String], provisioned: Boolean, awsTrials: Vector[AwsTrialResult], offsetSeconds: Long): Vector[DemoExportRecord] =
    val trials = awsTrials.map(adaptSingleTable(scenarioId, gsiNames, provisioned, _))
    DemoReportBuilder.build(trials).records.map(applyTickOffset(_, offsetSeconds))

  /** Run the v2 single-table Monte Carlo runner and write the staging JSONL; returns the record count. */
  def generateSingleTable(scenario: SingleTableScenario, masterSeed: Long, output: Path, offsetSeconds: Long)(using
    ActorSystem, Materializer, ExecutionContext
  ): Future[Int] =
    val gsiNames = scenario.globalSecondaryIndexes.map(_.indexName)
    new SingleTableMonteCarloRunner().run(scenario, masterSeed).map { result =>
      val records = recordsFor(scenario.scenarioId, gsiNames, scenario.usesProvisioning, result.trials, offsetSeconds)
      DemoJsonlExporter.write(output, records)
      records.size
    }(using summon[ExecutionContext])

  /** A Grafana URL for the dashboard, pre-filtered to this batch + scenario. */
  def viewUrl(grafanaBaseUrl: String, dashboardUid: String, dashboardSlug: String, batchId: String, scenarioId: String): String =
    val base = grafanaBaseUrl.stripSuffix("/")
    def enc(s: String): String = URLEncoder.encode(s, UTF_8)
    s"$base/d/$dashboardUid/$dashboardSlug?var-batch_id=${enc(batchId)}&var-scenarioId=${enc(scenarioId)}"
