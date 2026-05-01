package stochastacy.aws.transfer

import stochastacy.sim.{SimTime, TimedEvent}

/**
 * A generic, AWS-service-agnostic consumption event for "bytes were transferred across regions."
 *
 * Producers tag events with their `sourceService` so downstream cost reports can break down
 * cross-region transfer cost by which AWS service caused it. Slice 10 introduces this with
 * one producer (DynamoDB Global Tables replication, tagged `"DynamoDB"`); future producers
 * (S3 CRR, RDS read replicas, Lambda cross-region invocations, etc.) emit the same type into
 * the same usage/pricing pipeline.
 *
 * AWS bills inter-region data transfer per GB at the source region's outbound rate. The
 * `sourceRegion` field carries that origin; `destinationRegion` is the receiving region.
 */
final case class CrossRegionTransferEvent(
                                           override val eventTime: SimTime,
                                           override val usecase: Any,
                                           sourceRegion: String,
                                           destinationRegion: String,
                                           sourceService: String,
                                           bytes: Long
                                         ) extends TimedEvent:
  require(sourceRegion.nonEmpty, "sourceRegion must be non-empty")
  require(destinationRegion.nonEmpty, "destinationRegion must be non-empty")
  require(sourceService.nonEmpty, "sourceService must be non-empty")
  require(bytes >= 0L, s"bytes must be non-negative, got $bytes")
