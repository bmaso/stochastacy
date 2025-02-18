
package stochastacy

import scala.concurrent.duration.*


sealed trait WindowSize:
  val duration: Duration


object `1_m` extends WindowSize:
  override val duration: Duration = 60.seconds
  
object `5_m` extends WindowSize:
  override val duration: Duration = 300.seconds
