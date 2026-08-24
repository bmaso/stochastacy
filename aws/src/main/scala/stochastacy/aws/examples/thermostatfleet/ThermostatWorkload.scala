package stochastacy.aws.examples.thermostatfleet

import scala.collection.mutable

import org.apache.commons.rng.UniformRandomProvider

import stochastacy.aws.dynamodb.{DynamoDbRequest, DynamoDbTarget, PutItemRequest, QueryRequest, ReadConsistency, ScanRequest}
import stochastacy.core.component.Timed
import stochastacy.core.sampler.{PoissonSampler, UniformSampler}
import stochastacy.sim.SimTime

/**
 * The Thermostat-fleet single-region arrivals generator. Each tick, three flows: telemetry writes at a
 * **fleet-scaled** rate (`telemetryReportsPerDevicePerTick × fleetSize(tick)`, so it grows with the fleet),
 * a customer-support query on the `customer-devices` GSI, and a fleet-dashboard scan on the `fleet-alerts`
 * GSI (both eventually consistent — GSI reads cannot be strong). The temporal shaping of the telemetry
 * rate (spikes / vortex / bursts) is added in a later slice.
 */
object ThermostatWorkload:

  def arrivals(config: ThermostatConfig, rng: UniformRandomProvider): Vector[Timed[DynamoDbRequest]] =
    val telemetryRate = PoissonSampler(tick => config.telemetryReportsPerDevicePerTick * config.fleetSize(tick).toDouble)
    val queryRate     = PoissonSampler.constant(config.customerSupportQueryRatePerTick)
    val scanRate      = PoissonSampler.constant(config.fleetDashboardScanRatePerTick)
    val telemetryBytes = UniformSampler.constant(
      config.telemetryItemMeanBytes * (1.0 - config.telemetryItemBytesVariance),
      config.telemetryItemMeanBytes * (1.0 + config.telemetryItemBytesVariance)
    )

    def bytesFrom(sampler: UniformSampler, tick: Long): Long =
      math.max(1L, math.round(sampler.sample(tick, rng, ())._1))

    val customerDevices = DynamoDbTarget.Gsi(ThermostatConfig.CustomerDevicesGsiName)
    val fleetAlerts     = DynamoDbTarget.Gsi(ThermostatConfig.FleetAlertsGsiName)

    val out  = Vector.newBuilder[Timed[DynamoDbRequest]]
    var tick = 1L
    while tick <= config.simulationTicks do
      val perTick = mutable.ArrayBuffer.empty[(Double, DynamoDbRequest)]

      def emit(count: Int, mk: () => DynamoDbRequest): Unit =
        var i = 0
        while i < count do
          val payload = mk()
          val phi     = rng.nextDouble()
          perTick += ((phi, payload))
          i += 1

      emit(telemetryRate.sample(tick, rng, ())._1, () => PutItemRequest(bytesFrom(telemetryBytes, tick)))
      emit(queryRate.sample(tick, rng, ())._1,     () => QueryRequest(customerDevices, ReadConsistency.EventuallyConsistent))
      emit(scanRate.sample(tick, rng, ())._1,      () => ScanRequest(fleetAlerts, ReadConsistency.EventuallyConsistent))

      perTick.sortInPlaceBy(_._1)
      perTick.foreach { case (phi, payload) => out += Timed(payload, SimTime.of(tick), phi, config.scenarioId) }
      tick += 1

    out.result()
