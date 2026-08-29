package stochastacy.aws.examples.payments

import scala.collection.mutable

import org.apache.commons.rng.UniformRandomProvider

import stochastacy.aws.dynamodb.{DynamoDbRequest, GetItemRequest, TransactGetItemsRequest, TransactWriteItemsRequest, UpdateItemRequest}
import stochastacy.core.component.Timed
import stochastacy.core.sampler.PoissonSampler
import stochastacy.sim.SimTime

/**
 * The payments-ledger arrivals generator. Each tick draws a Poisson count of **transfers** and a Poisson
 * count of **balance checks**, and — per `config.useTransactions` — emits each as a single transactional
 * request or as the equivalent individual operations. The two forms do the same work, so billing them both
 * exposes the transactional 2× premium.
 */
object PaymentsLedgerWorkload:

  def arrivals(config: PaymentsLedgerConfig, rng: UniformRandomProvider): Vector[Timed[DynamoDbRequest]] =
    val transferRate = PoissonSampler.constant(config.transfersPerTick)
    val checkRate    = PoissonSampler.constant(config.balanceChecksPerTick)
    val perItemBytes = config.transactWriteItemsPerItemBytes
    val itemCount    = config.itemsPerTransaction

    val out  = Vector.newBuilder[Timed[DynamoDbRequest]]
    var tick = 1L
    while tick <= config.simulationTicks do
      val perTick = mutable.ArrayBuffer.empty[(Double, DynamoDbRequest)]

      def add(payload: DynamoDbRequest): Unit = perTick += ((rng.nextDouble(), payload))

      val transfers = transferRate.sample(tick, rng, ())._1
      var t = 0
      while t < transfers do
        if config.useTransactions then add(TransactWriteItemsRequest(perItemBytes))
        else perItemBytes.foreach(b => add(UpdateItemRequest(b)))
        t += 1

      val checks = checkRate.sample(tick, rng, ())._1
      var c = 0
      while c < checks do
        if config.useTransactions then add(TransactGetItemsRequest(itemCount))
        else { var i = 0; while i < itemCount do { add(GetItemRequest); i += 1 } }
        c += 1

      perTick.sortInPlaceBy(_._1)
      perTick.foreach { case (phi, payload) => out += Timed(payload, SimTime.of(tick), phi, config.scenarioId) }
      tick += 1

    out.result()
