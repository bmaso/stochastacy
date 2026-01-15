package stochastacy.test

import org.apache.pekko.stream.testkit.TestSubscriber

extension [T](probe: TestSubscriber.Probe[T])

  def assertEmptyStream(): Unit =
    probe.expectSubscription()
    probe.expectComplete()
