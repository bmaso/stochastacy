package stochastacy.examples.mm1

import org.json4s.JObject
import org.json4s.JsonDSL.*
import org.json4s.jackson.JsonMethods.{compact, render}

/**
 * The MM1 demo's output: one flat JSON object per trial for machines, and a table of estimate against closed form for
 * humans. The "within CI" column is informational here — the phase's theory spec asserts it.
 */
object MM1Report:

  /** One line per trial: the configuration it ran under and every metric it measured. */
  def jsonl(r: MM1EnsembleResult): String =
    r.trials.map { t =>
      val line: JObject =
        ("scenario" -> r.config.scenarioId) ~ ("master_seed" -> r.masterSeed) ~ ("trial" -> t.trialId) ~
          ("lambda" -> r.config.sessionsPerTick) ~ ("mu" -> r.config.serviceRate) ~ ("p" -> r.config.continueProb) ~
          ("think_time_mean" -> r.config.thinkTimeMean.getOrElse(0.0)) ~ ("rho" -> r.config.rho) ~
          ("ticks" -> r.config.simulationTicks) ~ ("warmup_ticks" -> r.config.warmupTicks) ~
          ("pages_per_session" -> t.pagesPerSession) ~ ("session_duration" -> t.sessionDuration) ~
          ("page_time" -> t.pageTime) ~ ("mean_in_system" -> t.meanInSystem) ~
          ("busy_fraction" -> t.busyFraction) ~ ("page_rate" -> t.pageRate) ~
          ("sessions_measured" -> t.sessionsMeasured) ~ ("sessions_in_flight" -> t.sessionsInFlight) ~
          ("pages_measured" -> t.pagesMeasured) ~ ("windows_measured" -> t.windowsMeasured)
      compact(render(line))
    }.mkString("\n") + (if r.trials.isEmpty then "" else "\n")

  private def row(name: String, e: Estimate, theory: Double): String =
    f"    $name%-24s ${e.mean}%10.4f ± ${e.stdErr}%-8.4f ${theory}%10.4f     ${if e.contains(theory) then "yes" else "NO"}%s\n"

  def summary(r: MM1EnsembleResult): String =
    val c  = r.config
    val sb = new StringBuilder
    sb ++= s"MM1 demo — ${c.scenarioId} (${r.trials.size} trials × ${c.simulationTicks} ticks, "
    sb ++= f"warm-up ${c.warmupTicks}, λ=${c.sessionsPerTick}%.1f μ=${c.serviceRate}%.1f p=${c.continueProb}%.2f"
    sb ++= c.thinkTimeMean.fold("")(m => f" think=$m%.3f")
    sb ++= f", λ_eff=${c.lambdaEff}%.1f ρ=${c.rho}%.3f)\n"
    sb ++= "    metric                     estimate ± stderr       theory     within CI\n"
    sb ++= row("pages per session", r.pagesPerSession, MM1Theory.pagesPerSession(c))
    sb ++= row("mean number in system", r.meanInSystem, MM1Theory.meanInSystem(c))
    sb ++= row("time per page", r.pageTime, MM1Theory.pageTime(c))
    sb ++= row("session duration", r.sessionDuration, MM1Theory.sessionDuration(c))
    sb ++= row("busy fraction", r.busyFraction, MM1Theory.busyFraction(c))
    sb ++= row("page rate", r.pageRate, MM1Theory.pageRate(c))
    val inFlight = r.trials.map(_.sessionsInFlight).sum
    val measured = r.trials.map(_.sessionsMeasured).sum
    sb ++= s"    sessions measured: $measured (excluded, still in flight at the horizon: $inFlight)\n"
    sb.result()
