package co.eafit.dfsha.datanode;

import co.eafit.dfsha.common.Env;
import io.grpc.Server;
import io.grpc.ServerBuilder;

/**
 * Servidor del DataNode (bloques). NO existia en el repo: se implementa desde cero.
 * Almacenamiento (WriteBlock/ReadBlock/DeleteBlock): Mendel, en BlockStorageService.
 * Registro y heartbeat: Martin (Fase 2), en clases propias. Este archivo solo las ensambla.
 */
public final class DataNodeMain {
  public static void main(String[] args) throws Exception {
    int port = Env.getInt("GRPC_PORT", 50061);
    Server server = ServerBuilder.forPort(port).build().start(); // TODO: addService(...)
    System.out.println("DFSha DataNode (esqueleto) escuchando en " + port);
    Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown));
    server.awaitTermination();
  }
}
