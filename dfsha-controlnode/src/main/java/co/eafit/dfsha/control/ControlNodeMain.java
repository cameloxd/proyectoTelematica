package co.eafit.dfsha.control;

import co.eafit.dfsha.common.Env;
import co.eafit.dfsha.control.db.Database;
import co.eafit.dfsha.control.repository.JdbcDataNodeRepository;
import co.eafit.dfsha.control.service.NodeRegistryGrpcService;
import io.grpc.Server;
import io.grpc.ServerBuilder;

/**
 * Servidor del ControlNode (metadatos). Responsable: Camilo.
 * Referencia del bootstrap en memoria: docs/reference/ControlNodeMain.bootstrap.java.txt
 */
public final class ControlNodeMain {
  public static void main(String[] args) throws Exception {
    int port = Env.getInt("GRPC_PORT", 50051);
    Database database = Database.fromEnvironment();
    Server server = ServerBuilder.forPort(port)
        .addService(new NodeRegistryGrpcService(new JdbcDataNodeRepository(database.dataSource())))
        .build().start();
    System.out.println("DFSha ControlNode escuchando en " + port + " con PostgreSQL");
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      server.shutdown();
      database.close();
    }));
    server.awaitTermination();
  }
}
