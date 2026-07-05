package stochastacy.aws.dynamodb.client

/** Jitter applied to the exponential backoff delay before a retry attempt.
 *
 *  Real AWS SDKs use one of these three strategies when scheduling a retry:
 *
 *  - `None`  — deterministic exponential backoff (no randomization).
 *  - `Full`  — draw the delay uniformly from `[0, base * 2^attempt]`; the AWS
 *              "Full Jitter" pattern, spreads retries uniformly across the
 *              backoff window and minimises herding.
 *  - `Equal` — half deterministic, half randomised: `base * 2^attempt / 2 +
 *              Uniform(0, base * 2^attempt / 2)`; a compromise between herd
 *              avoidance and a minimum wait guarantee.
 *
 *  The concrete delay-computation math lives in Slice B (backoff distribution).
 *  This enum is only the configuration marker consumed there. */
enum JitterStrategy:
  case None
  case Full
  case Equal
