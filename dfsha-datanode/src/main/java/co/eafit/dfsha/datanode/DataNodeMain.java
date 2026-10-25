package co.eafit.dfsha.datanode;

import co.eafit.dfsha.common.Env;
import co.eafit.dfsha.common.Rpc;
import co.eafit.dfsha.common.Tokens;
import io.grpc.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DataNode: guarda bloques. Solo arma las piezas:
 * BlockStore (disco cifrado), DataNodeServiceImpl (gRPC), ControlLink (registro, heartbeat, colas)
 * y Replicator (copia de réplicas).
 */
public final class DataNodeMain {
  private static final Logger log = LoggerFactory.getLogger(DataNodeMain.class);

  public static void main(String[] args) throws Exception {
    String name = Env.get("NODE_NAME", "datanode-1");
    int port = Env.getInt("GRPC_PORT", 50061);

    BlockStore store = BlockStore.fromEnv(name);
    ControlLink cluster = new ControlLink(name, store);
    DataNodeServiceImpl service = new DataNodeServiceImpl(store, Tokens.fromEnv(), cluster);   // solo clave pública

    Server server = Rpc.server(port, service.bindService());   // 1. ya puede recibir bloques
    cluster.register(port);                                    // 2. el ControlNode ya puede asignarle bloques
    cluster.start(new Replicator(store, cluster));             // 3. heartbeats y colas de RabbitMQ
    log.info("DataNode {} listo en el puerto {}", name, port);

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      server.shutdown();
      cluster.stop();
    }));
    server.awaitTermination();
  }
}
