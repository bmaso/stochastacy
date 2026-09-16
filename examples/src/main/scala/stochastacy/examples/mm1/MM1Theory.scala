package stochastacy.examples.mm1

/**
 * The closed forms this demo is measured against. A single FIFO server fed by Poisson arrivals where each finished
 * job rejoins immediately with probability `p` is a Jackson network with a product-form solution: the server behaves
 * as M/M/1 at the effective rate `λ_eff = λ/(1−p)`.
 *
 * Think time adds an infinite-server stage, which leaves the server's own quantities untouched and lengthens only the
 * session duration — by one think time per page after the first.
 */
object MM1Theory:

  /** Pages per session: geometric with continuation probability `p`. */
  def pagesPerSession(c: MM1Config): Double = 1.0 / (1.0 - c.continueProb)

  /** Time-average number in the system (waiting plus in service): `ρ / (1 − ρ)`. */
  def meanInSystem(c: MM1Config): Double = c.rho / (1.0 - c.rho)

  /** `P(n in system)` — geometric: `(1 − ρ) ρⁿ`. */
  def inSystemProbability(c: MM1Config, n: Int): Double = (1.0 - c.rho) * math.pow(c.rho, n.toDouble)

  /** Mean time for one page (queue wait + service): `1 / (μ − λ_eff)`. */
  def pageTime(c: MM1Config): Double = 1.0 / (c.serviceRate - c.lambdaEff)

  /** Mean end-to-end session duration: `1 / ((1−p)μ − λ)`, plus one think time per page after the first. */
  def sessionDuration(c: MM1Config): Double =
    val service = 1.0 / ((1.0 - c.continueProb) * c.serviceRate - c.sessionsPerTick)
    service + c.thinkTimeMean.fold(0.0)(mean => (pagesPerSession(c) - 1.0) * mean)

  /** Fraction of time the server is busy: `ρ`. */
  def busyFraction(c: MM1Config): Double = c.rho

  /** The rate of page requests reaching the server. */
  def pageRate(c: MM1Config): Double = c.lambdaEff
