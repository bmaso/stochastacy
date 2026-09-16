package stochastacy.examples.mm1

import stochastacy.core.component.circuit.{Circuit, CircuitNode}

/**
 * The demo's circuit: client → server → back to the client's feedback. The loop closes **inside** a tick — at the
 * default load a session's pages all complete within one tick — which is precisely what a Pekko-stage pipeline could
 * not express. The circuit reports only through consumption facts, so its forward output type is `Nothing`.
 */
object MM1Circuit:

  type ClientNodeHandle = CircuitNode[Unit, Session, PageResponse, PageRequest, MM1Fact, Nothing]
  type ServerNodeHandle = CircuitNode[ServerState, PageRequest, Nothing, PageResponse, MM1Fact, Nothing]

  final case class Handles(client: ClientNodeHandle, server: ServerNodeHandle)

  def build(config: MM1Config): (Circuit[Session, Nothing, MM1Fact], Handles) =
    Circuit.buildWith[Session, Nothing, MM1Fact] { b =>
      val client = b.node("client", new ClientNode(config))
      val server = b.node("server", new ServerNode(config))
      b.input(client.in)
      b.connect(client.out, server.in)
      b.connect(server.out, client.fb)
      b.consumption(client.consumption)
      b.consumption(server.consumption)
      Handles(client, server)
    }
