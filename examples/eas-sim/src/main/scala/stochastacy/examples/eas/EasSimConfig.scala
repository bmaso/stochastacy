package stochastacy.examples.eas

/**
 * Configuration for the alerts table UseCaseSampler.
 *
 * @param region                 GSI partition key used by all A1 Query requests.
 *                               Fixed for all queries — this is the hot partition.
 * @param alertId                Base-table partition key used by A2 GetItem and A3 PutItem.
 *                               Fixed — all users fetch the same alert during a burst.
 * @param projectedItemMinBytes  Minimum bytes per projected GSI item (A1 reads).
 *                               GSI projection excludes the message field.
 * @param projectedItemMaxBytes  Maximum bytes per projected GSI item (A1 reads).
 * @param scannedItemsMin        Minimum items scanned per A1 Query (before FilterExpression).
 * @param scannedItemsMax        Maximum items scanned per A1 Query (before FilterExpression).
 * @param fullItemLogNormalMu    Log-normal μ for full alert item size in bytes (A2/A3).
 *                               Median ≈ e^8.41 ≈ 4,500 bytes.
 * @param fullItemLogNormalSigma Log-normal σ for full alert item size in bytes (A2/A3).
 *                               σ=0.4 spans roughly 2,000–8,000 bytes.
 */
case class EasAlertsConfig(
  region:                 String = "northeast",
  alertId:                String = "alert-001",
  projectedItemMinBytes:  Long   = 100L,
  projectedItemMaxBytes:  Long   = 250L,
  scannedItemsMin:        Int    = 1,
  scannedItemsMax:        Int    = 3,
  fullItemLogNormalMu:    Double = 8.41,
  fullItemLogNormalSigma: Double = 0.4
):
  require(projectedItemMinBytes > 0, "projectedItemMinBytes must be positive")
  require(projectedItemMaxBytes >= projectedItemMinBytes,
    "projectedItemMaxBytes must be >= projectedItemMinBytes")
  require(scannedItemsMin >= 1, "scannedItemsMin must be >= 1")
  require(scannedItemsMax >= scannedItemsMin, "scannedItemsMax must be >= scannedItemsMin")
  require(fullItemLogNormalSigma > 0, "fullItemLogNormalSigma must be positive")
  require(region.nonEmpty, "region must be non-empty")
  require(alertId.nonEmpty, "alertId must be non-empty")

/**
 * Configuration for the user-alert-status table UseCaseSampler.
 *
 * @param userPopulation  Size of the user key space. Used to sample distributed partition
 *                        keys for S1/S2/S3 writes — each write lands on a different userId.
 * @param itemMinBytes    Minimum bytes for a status record (S1 PutItem, S2/S3 UpdateItem).
 * @param itemMaxBytes    Maximum bytes for a status record.
 */
case class EasUserAlertStatusConfig(
  userPopulation: Long = 500_000L,
  itemMinBytes:   Long = 200L,
  itemMaxBytes:   Long = 400L
):
  require(userPopulation > 0, "userPopulation must be positive")
  require(itemMinBytes > 0, "itemMinBytes must be positive")
  require(itemMaxBytes >= itemMinBytes, "itemMaxBytes must be >= itemMinBytes")
