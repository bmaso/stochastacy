package stochastacy.aws.dynamodb.pricing

trait PricingSchedule:
  def ratesAt(region: String, tick: Long): DynamoDbPricingRates
  def defaultRates: DynamoDbPricingRates

object PricingSchedule:
  val default: PricingSchedule = uniform(DynamoDbPricingRates.phase1Default)

  def uniform(rates: DynamoDbPricingRates): PricingSchedule =
    StaticPricingSchedule(Map.empty, rates)

  def byRegion(
    ratesByRegion: Map[String, DynamoDbPricingRates],
    fallback: DynamoDbPricingRates = DynamoDbPricingRates.phase1Default
  ): PricingSchedule = StaticPricingSchedule(ratesByRegion, fallback)

final class StaticPricingSchedule(
  private val ratesByRegion: Map[String, DynamoDbPricingRates],
  private val fallback: DynamoDbPricingRates
) extends PricingSchedule:
  def ratesAt(region: String, tick: Long): DynamoDbPricingRates =
    ratesByRegion.getOrElse(region, fallback)
  def defaultRates: DynamoDbPricingRates = fallback
