package stochastacy.examples.mm1

import org.apache.commons.rng.UniformRandomProvider

/** A user session arriving at the client. */
final case class Session(id: Long)

/** One page of a session. `sessionStartedAt` rides along so the client can close out a session's end-to-end duration
 *  without holding per-session state. */
final case class PageRequest(session: Long, page: Int, sessionStartedAt: Double)

/** The server's answer for one page. */
final case class PageResponse(session: Long, page: Int, sessionStartedAt: Double)

/** What the demo measures. */
enum MM1Fact:
  /** A session finished: how many pages it took, when it started, and its end-to-end duration. */
  case SessionCompleted(pages: Int, startedAt: Double, duration: Double)

  /** One page was served, `sojourn` after it reached the server (queue wait + service). Stamped at completion. */
  case PageServed(sojourn: Double)

  /** Exact integrals over the tick window `[windowStart, windowStart + 1)`: `inSystem` is ∫N(t) dt (requests
   *  arrived but not yet departed) and `busy` is the time the server spent serving. */
  case WindowIntegral(windowStart: Long, inSystem: Double, busy: Double)

  /** Time spent at each number-in-system level over the tick window `[windowStart, windowStart + 1)`: `times(n)` for
   *  `N = n`, with the last slot collecting `N ≥ times.size − 1`. The slots sum to the window length. */
  case WindowLevels(windowStart: Long, times: Vector[Double])

/** Exponential draws by inverse transform. The distribution samplers in Commons Statistics bind their RNG at
 *  construction, but a component sampler receives its RNG per call, so the inverse transform is used here — it is
 *  exact for the exponential and allocates nothing per draw. */
private[mm1] object Exponential:
  def draw(rate: Double, rng: UniformRandomProvider): Double =
    -math.log1p(-rng.nextDouble()) / rate
