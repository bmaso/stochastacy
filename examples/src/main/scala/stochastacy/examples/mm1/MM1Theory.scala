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

  /** The probability of a queue-length slot as the demo reports it: `P(N = n)` for `n < queueLevels − 1`, and the tail
   *  `P(N ≥ queueLevels − 1) = ρ^(queueLevels − 1)` for the last slot. */
  def inSystemSlotProbability(c: MM1Config, slot: Int): Double =
    val tail = c.queueLevels - 1
    if slot < tail then inSystemProbability(c, slot) else math.pow(c.rho, tail.toDouble)

  /**
   * How many queue-length slots to report at utilisation `rho`: individual levels only while `P(N = n) ≥ minProbability`,
   * then one tail slot for everything above, capped at `maxSlots`.
   *
   * A slot whose level is rarely visited is a zero-inflated per-trial variable — at ρ = 0.5, 55 % of trials spent no
   * time at all in `N ≥ 11` — and the normal approximation behind a z-score check is weakest exactly there. The rule is
   * stated up front and applied to every load: at ρ = 0.5 it keeps levels 0–5 plus `N ≥ 6`; at ρ = 0.8 and 0.9 every
   * level to 10 qualifies and the cap applies.
   */
  def adequateQueueLevels(rho: Double, minProbability: Double = 0.01, maxSlots: Int = 12): Int =
    val qualifying = Iterator.from(0).takeWhile(n => (1.0 - rho) * math.pow(rho, n.toDouble) >= minProbability).size
    math.min(maxSlots, math.max(2, qualifying + 1)) // the qualifying levels, plus one tail slot

  /** `P(pages = k)` — geometric from 1: `(1 − p) p^(k−1)`. */
  def pagesProbability(c: MM1Config, pages: Int): Double =
    (1.0 - c.continueProb) * math.pow(c.continueProb, (pages - 1).toDouble)

  /** The probability of a pages-per-session slot as the demo reports it: slot `s` is `s + 1` pages, and the last slot
   *  is the tail `P(pages ≥ pageLevels) = p^(pageLevels − 1)`. */
  def pagesSlotProbability(c: MM1Config, slot: Int): Double =
    val tail = c.pageLevels - 1
    if slot < tail then pagesProbability(c, slot + 1) else math.pow(c.continueProb, tail.toDouble)

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
