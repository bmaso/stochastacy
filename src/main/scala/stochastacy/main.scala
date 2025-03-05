package stochastacy

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.stream.Materializer
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.Logger
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.json4s.jackson.Serialization
import org.json4s.jackson.Serialization.{read, write}
import org.json4s.{DefaultFormats, jackson}
import stochastacy.graphs.HelloWorldGraph
import stochastacy.graphs.PoissonWindowedEventSource

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.util.{Failure, Success}

case class EventCountSet(windowSizeMs: Long, events: Seq[PoissonWindowedEventSource.EventCount])

@main def Stochastacy(): Unit =
  given system: ActorSystem = ActorSystem("StochastacySystem")
  given materializer: Materializer = Materializer(system)
  given ExecutionContext = system.dispatcher

  val config = ConfigFactory.load()
  val host = config.getString("app.host")
  val port = config.getInt("app.port")
  
  val logger = Logger("Stochastacy")

  // Define API route
  val route = {
    import org.apache.pekko.http.scaladsl.server.Directives._
    import com.github.pjfanning.pekkohttpjson4s.Json4sSupport._

    given Serialization.type =
      jackson.Serialization // or native.Serialization

    given DefaultFormats.type = DefaultFormats

    concat(
      path("hello") {
        get {
          onSuccess(HelloWorldGraph.run()) { result =>
            complete(StatusCodes.OK, HelloWorldGraph.HelloResponse(result))
          }
        }
      },
      path("poisson-event-series") {
        logger.debug("GET poisson-event-series")

        get {
          parameters("totalDurationMs".as[Int], "windowSizeMs".as[Int], "expectedEventsPerSec".as[Double]) { (totalMs, windowMs, lambda) =>
            logger.debug(s"params (totalDurationMs $totalMs, windowSizeMs $windowMs, expected/sec $lambda")

            val totalDurationMs = totalMs.millis
            val windowSizeMs = windowMs.millis

            if (totalDurationMs.toMillis % totalDurationMs.toMillis != 0) {
              complete(StatusCodes.BadRequest, "Total duration must be a multiple of window duration")
            } else {
              val future = PoissonWindowedEventSource(totalDurationMs, windowSizeMs, lambda).runWith(Sink.seq)
              onSuccess(future) { events =>
                complete(EventCountSet(windowSizeMs.toMillis, events))
              }
            }
          }
        }
      }
    )
  }

  // Start the HTTP server
  val bindingFuture = Http().newServerAt(host, port).bind(route)

  bindingFuture.onComplete {
    case Success(binding) =>
      println(s"Stochastacy is running at http://${binding.localAddress}/")
    case Failure(exception) =>
      println(s"Failed to bind HTTP endpoint, terminating: ${exception.getMessage}")
      summon[ActorSystem].terminate()
  }

  // Ensure the ActorSystem remains active and wait for the HTTP server to bind
  bindingFuture.flatMap { _ =>
    summon[ActorSystem].whenTerminated
  }.onComplete { _ =>
    println("ActorSystem terminated, shutting down the application...")
    summon[ActorSystem].terminate()  // Ensure system is fully terminated
  }

  // Wait for ActorSystem termination
  println("Waiting for ActorSystem to terminate...")
  Await.result(summon[ActorSystem].whenTerminated, Duration.Inf)
