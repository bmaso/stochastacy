package stochastacy.visualizer

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.json4s.DefaultFormats
import org.json4s.jackson.Serialization
import stochastacy.workload.{EvaluationResult, WorkloadDslException, WorkloadEvaluator}

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

class WorkloadVisualizerServer:

  private implicit val jsonFormats: org.json4s.Formats = DefaultFormats

  private val jsContentType: ContentType =
    ContentType(MediaTypes.`application/javascript`, () => HttpCharsets.`UTF-8`)

  private def classpathResource(path: String): Option[Array[Byte]] =
    Option(getClass.getClassLoader.getResourceAsStream(path)).map { stream =>
      try stream.readAllBytes() finally stream.close()
    }

  private def serveResource(resourcePath: String, ct: ContentType): Route =
    classpathResource(resourcePath) match
      case Some(bytes) => complete(HttpEntity(ct, bytes))
      case None        => complete(StatusCodes.NotFound -> s"Not found: $resourcePath")

  private[visualizer] val route: Route = concat(
    (path("api" / "evaluate") & post) {
      parameters("workload", "ticks".as[Long], "seed".as[Long]) { (workloadName, ticks, seed) =>
        entity(as[String]) { yaml =>
          Try(WorkloadEvaluator.evaluate(yaml, workloadName, ticks, seed)) match
            case Success(result) =>
              val json = Serialization.write(result)
              complete(HttpEntity(ContentTypes.`application/json`, json))
            case Failure(e: WorkloadDslException) =>
              complete(StatusCodes.BadRequest -> e.getMessage)
            case Failure(e) =>
              complete(StatusCodes.InternalServerError -> e.getMessage)
        }
      }
    },
    pathEndOrSingleSlash {
      serveResource("stochastacy/visualizer/web/index.html", ContentTypes.`text/html(UTF-8)`)
    },
    path("app.js") {
      serveResource("stochastacy/visualizer/web/app.js", jsContentType)
    },
    path("vendor" / "chart.umd.min.js") {
      serveResource("stochastacy/visualizer/web/vendor/chart.umd.min.js", jsContentType)
    }
  )

  def start(host: String, port: Int)(implicit system: ActorSystem): Future[Http.ServerBinding] =
    Http().newServerAt(host, port).bind(route)
