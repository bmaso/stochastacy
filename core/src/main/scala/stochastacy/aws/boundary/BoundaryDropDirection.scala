package stochastacy.aws.boundary

/**
 * Which side of a [[SystemBoundaryStage]] dropped a crossing — the two are
 * genuinely different failure modes and carry different cost/correctness
 * implications, so telemetry must keep them apart.
 *
 *  - [[Ingress]] — the request was dropped before reaching the service.  No
 *    service capacity is consumed and no state changes; the client times out
 *    and retries.  (connection-refused, ingress rate-limit/shed, SYN drop, ...)
 *  - [[Egress]] — the service processed the request (capacity consumed, state
 *    mutated) but the response was lost on the way back.  The client times out
 *    and retries anyway, so the retry is duplicate work / a double-write.
 *    (client read-timeout on a completing server, egress saturation, ...)
 */
enum BoundaryDropDirection:
  case Ingress
  case Egress
