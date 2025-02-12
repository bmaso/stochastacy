package stochastacy.graphs

import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Flow, RunnableGraph, Sink, Source}

import scala.concurrent.{ExecutionContext, Future}


// Define a Pekko Streams graph placeholder
object HelloWorldGraph:
  case class HelloResponse(message: String)

  def run()(using materializer: Materializer, executionContext: ExecutionContext): Future[String] =
    val source = Source.single("Hello, Stochastacy Graph!")
    val flow = Flow[String].map(_.toUpperCase)
    val sink = Sink.head[String]

    source.via(flow).runWith(sink)

