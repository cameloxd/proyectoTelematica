package co.eafit.dfsha.control;

import co.eafit.dfsha.common.Env;
import co.eafit.dfsha.common.Rabbit;
import co.eafit.dfsha.common.Rpc;
import co.eafit.dfsha.common.Tokens;
import co.eafit.dfsha.grpc.v1.ControlServiceGrpc;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ControlNode: el servidor de metadatos. Solo arma las piezas; la lógica está en
 * ControlServiceImpl (clientes), NodeRegistryImpl (DataNodes) y ClusterMonitor (tareas de fondo).
 *
 * <p>No guarda estado en memoria: todo está en PostgreSQL, por eso se pueden correr varios
 * ControlNodes a la vez y el cliente cambia a otro si uno se cae.
 */
public final class ControlNodeMain {
  private static final Logger log = LoggerFactory.getLogger(ControlNodeMain.class);

  public static void main(String[] args) throws Exception {
    int port = Env.getInt("GRPC_PORT", 50051);
    Tokens tokens = Tokens.fromEnv();                       // clave privada: el ControlNode firma
    try (Db db = Db.fromEnv(); Rabbit rabbit = Rabbit.connect("controlnode-" + port)) {
      ControlServiceImpl control = new ControlServiceImpl(db, tokens);
      NodeRegistryImpl registry = new NodeRegistryImpl(db, Env.secret("REGISTRATION_KEY", "dev-registration-key"));
      ClusterMonitor monitor = new ClusterMonitor(db, tokens, rabbit);
      DeleteOutbox deletes = new DeleteOutbox(db, rabbit);
      Server server = null;
      try {
        deletes.start();                                     // valida migración antes de abrir el RPC
        // Todo ControlService exige sesión, menos Register y Login.
        var auth = Rpc.requireToken(token -> tokens.verify(token, Tokens.SESSION), Set.of(
            ControlServiceGrpc.getRegisterMethod().getFullMethodName(),
            ControlServiceGrpc.getLoginMethod().getFullMethodName()));
        // NodeRegistryService usa node_token, no token de usuario.
        server = Rpc.server(port, ServerInterceptors.intercept(control, auth), registry.bindService());
        monitor.start();
        Server running = server;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
          running.shutdown();
          monitor.stop();
          deletes.stop();
          rabbit.close();
          db.close();
        }));
        log.info("ControlNode listo en el puerto {}", port);
        server.awaitTermination();
      } finally {
        // Un fallo de arranque tampoco debe dejar vivos los trabajadores ni el servidor.
        if (server != null) server.shutdownNow();
        monitor.stop();
        deletes.stop();
      }
    }
  }
}
