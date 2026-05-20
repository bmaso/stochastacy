package stochastacy.visualizer

import org.apache.pekko.actor.ActorSystem

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext}
import scala.util.{Failure, Success}

@main def WorkloadVisualizerBridge(args: String*): Unit =
  val port: Int =
    val idx = args.indexOf("--port")
    if idx >= 0 && idx + 1 < args.length then
      args(idx + 1).toIntOption.getOrElse(7777)
    else 7777
  val openBrowser = args.contains("--open")

  implicit val system: ActorSystem  = ActorSystem("workload-visualizer")
  implicit val ec: ExecutionContext = system.dispatcher

  val server = new WorkloadVisualizerServer()
  server.start("localhost", port).onComplete {
    case Success(binding) =>
      val actualPort = binding.localAddress.getPort
      val url        = s"http://localhost:$actualPort"
      println(s"Workload visualizer running at $url")
      println("Press Ctrl-C to stop.")
      if openBrowser then
        try java.awt.Desktop.getDesktop.browse(java.net.URI.create(url))
        catch case e: Exception => println(s"Could not open browser automatically: ${e.getMessage}")
    case Failure(e) =>
      println(s"Failed to start server: ${e.getMessage}")
      system.terminate()
  }

  // Block the main thread until the ActorSystem is shut down (Ctrl-C triggers JVM shutdown hook)
  Await.result(system.whenTerminated, Duration.Inf)
