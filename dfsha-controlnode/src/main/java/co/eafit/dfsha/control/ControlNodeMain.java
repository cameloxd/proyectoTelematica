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
    Db db = Db.fromEnv();
    Tokens tokens = Tokens.fromEnv();                       // clave privada: el ControlNode firma
    Rabbit rabbit = Rabbit.connect("controlnode-" + port);

    ControlServiceImpl control = new ControlServiceImpl(db, tokens, rabbit);
    NodeRegistryImpl registry = new NodeRegistryImpl(db, Env.secret("REGISTRATION_KEY", "dev-registration-key"));
    ClusterMonitor monitor = new ClusterMonitor(db, tokens, rabbit);

    // Todo ControlService exige token de sesión, menos Register y Login (con ellos se obtiene).
    var auth = Rpc.requireToken(token -> tokens.verify(token, Tokens.SESSION), Set.of(
        ControlServiceGrpc.getRegisterMethod().getFullMethodName(),
        ControlServiceGrpc.getLoginMethod().getFullMethodName()));
    // NodeRegistryService no usa token de usuario: los DataNodes se autentican con su node_token.
    Server server = Rpc.server(port, ServerInterceptors.intercept(control, auth), registry.bindService());
    monitor.start();
    log.info("ControlNode listo en el puerto {}", port);

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      server.shutdown();
      monitor.stop();
      rabbit.close();
      db.close();
    }));
    server.awaitTermination();
  }
}
