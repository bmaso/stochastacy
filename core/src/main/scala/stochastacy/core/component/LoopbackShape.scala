package stochastacy.core.component

import org.apache.pekko.stream.{Inlet, Outlet, Shape}

import stochastacy.sim.TimedElement

/**
 * The stream shape of a **loopback-capable** component (see [[LoopbackComponentSampler]]): the ordinary
 * request inlet and forward/consumption outlets, plus a **feedback inlet** (`fbIn`, the loop-in) and a
 * **tap outlet** (`tapOut`, the loop-out). An external stage carries `tapOut` back to peers' `fbIn` after a
 * delay; the transducer forwards `tapOut`'s tick markers eagerly (driven by `in`) so that delayed self-loop
 * is deadlock-free.
 */
final class LoopbackShape[In, Fb, Out, Cons, Tap](
  val in:      Inlet[TimedElement[Timed[In]]],
  val fbIn:    Inlet[TimedElement[Timed[Fb]]],
  val fwdOut:  Outlet[TimedElement[Timed[Out]]],
  val consOut: Outlet[TimedElement[Timed[Cons]]],
  val tapOut:  Outlet[TimedElement[Timed[Tap]]]
) extends Shape:
  override def inlets:  Seq[Inlet[?]]  = List(in, fbIn)
  override def outlets: Seq[Outlet[?]] = List(fwdOut, consOut, tapOut)
  override def deepCopy(): LoopbackShape[In, Fb, Out, Cons, Tap] =
    new LoopbackShape(in.carbonCopy(), fbIn.carbonCopy(), fwdOut.carbonCopy(), consOut.carbonCopy(), tapOut.carbonCopy())
