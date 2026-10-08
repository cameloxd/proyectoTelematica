package co.eafit.dfsha.control;

import co.eafit.dfsha.common.Env;
import io.grpc.Server;
import io.grpc.ServerBuilder;

/**
 * Servidor del ControlNode (metadatos). Responsable: Camilo.
 * Referencia del bootstrap en memoria: docs/reference/ControlNodeMain.bootstrap.java.txt
 */
public final class ControlNodeMain {
  public static void main(String[] args) throws Exception {
    int port = Env.getInt("GRPC_PORT", 50051);
    Server server = ServerBuilder.forPort(port).build().start(); // TODO: addService(...)
    System.out.println("DFSha ControlNode (esqueleto) escuchando en " + port);
    Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown));
    server.awaitTermination();
  }
}
