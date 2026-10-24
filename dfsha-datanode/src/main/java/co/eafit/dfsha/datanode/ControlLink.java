package co.eafit.dfsha.datanode;

import co.eafit.dfsha.common.Env;
import co.eafit.dfsha.common.Rabbit;
import co.eafit.dfsha.common.Rpc;
import co.eafit.dfsha.grpc.v1.BlockDeleteTask;
import co.eafit.dfsha.grpc.v1.Heartbeat;
import co.eafit.dfsha.grpc.v1.NodeRegistryServiceGrpc;
import co.eafit.dfsha.grpc.v1.RegisterDataNodeRequest;
import co.eafit.dfsha.grpc.v1.RegisterDataNodeResponse;
import co.eafit.dfsha.grpc.v1.ReplicationTask;
import co.eafit.dfsha.grpc.v1.ReportBlockStoredRequest;
import io.grpc.StatusRuntimeException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * La conexión del DataNode con el resto del clúster:
 * <ul>
 *   <li>al arrancar se registra en un ControlNode (reintenta hasta lograrlo),</li>
 *   <li>cada HEARTBEAT_INTERVAL_SECONDS publica un heartbeat en RabbitMQ,</li>
 *   <li>avisa al ControlNode cada bloque que guarda (ReportBlockStored),</li>
 *   <li>escucha sus dos colas de RabbitMQ: copiar réplicas y borrar bloques.</li>
 * </ul>
 */
public final class ControlLink {
  private static final Logger log = LoggerFactory.getLogger(ControlLink.class);

  /** Transferencias en curso; las cuenta DataNodeServiceImpl y viajan en el heartbeat (carga del nodo). */
  final AtomicInteger activeTransfers = new AtomicInteger();

  private final String name;
  private final BlockStore store;
  private final long capacity = Env.getLong("DATANODE_CAPACITY_BYTES", 5L * 1024 * 1024 * 1024);
  private final Rpc.Failover controlNodes = new Rpc.Failover(Env.getList("CONTROLNODE_ENDPOINTS", "localhost:50051"));
  private final Rabbit rabbit;
  private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
  private volatile String nodeId;
  private volatile String nodeToken;

  public ControlLink(String name, BlockStore store) {
    this.name = name;
    this.store = store;
    this.rabbit = Rabbit.connect(name);
  }

  /** RegisterDataNode. ADVERTISED_ENDPOINT es para los clientes; INTERNAL_ENDPOINT para los otros nodos. */
  public void register(int port) {
    String advertised = Env.get("ADVERTISED_ENDPOINT", "localhost:" + port);
    RegisterDataNodeRequest request = RegisterDataNodeRequest.newBuilder()
        .setNodeName(name)
        .setAdvertisedEndpoint(advertised)
        .setInternalEndpoint(Env.get("INTERNAL_ENDPOINT", advertised))
        .setCapacityBytes(capacity)
        .setRegistrationKey(Env.secret("REGISTRATION_KEY", "dev-registration-key"))
        .build();
    while (true) {
      try {
        RegisterDataNodeResponse response = controlNodes.call(ch -> NodeRegistryServiceGrpc.newBlockingStub(ch)
            .withDeadlineAfter(10, TimeUnit.SECONDS).registerDataNode(request));
        nodeId = response.getDatanodeId();
        nodeToken = response.getNodeToken();
        log.info("Registrado en el ControlNode como {} (id {})", name, nodeId);
        return;
      } catch (StatusRuntimeException e) {
        log.warn("No pude registrarme ({}); reintento en 3 s", e.getStatus());
        sleep(3000);
      }
    }
  }

  /** Empieza a escuchar las colas de este nodo y a mandar heartbeats. */
  public void start(Replicator replicator) throws Exception {
    rabbit.declareNodeQueues(nodeId);
    rabbit.consume(Rabbit.replicationQueue(nodeId), body -> replicator.copy(ReplicationTask.parseFrom(body)));
    rabbit.consume(Rabbit.deleteQueue(nodeId), body -> {
      String blockId = BlockDeleteTask.parseFrom(body).getBlockId();
      store.delete(blockId);
      log.info("Bloque {} borrado del disco", blockId);
    });
    timer.scheduleAtFixedRate(this::sendHeartbeat, 0, Env.getLong("HEARTBEAT_INTERVAL_SECONDS", 5), TimeUnit.SECONDS);
  }

  private void sendHeartbeat() {
    try {
      Heartbeat heartbeat = Heartbeat.newBuilder()
          .setDatanodeId(nodeId).setNodeToken(nodeToken)
          .setCapacityBytes(capacity).setFreeBytes(Math.max(0, capacity - store.usedBytes()))
          .setActiveTransfers(activeTransfers.get()).setStoredBlocks(store.blockCount())
          .build();
      rabbit.publish(Rabbit.heartbeatKey(nodeId), heartbeat.toByteArray(), false);
    } catch (Exception e) {
      log.warn("No pude enviar el heartbeat: {}", e.toString());
    }
  }

  /** "Ya guardé este bloque": así el ControlNode marca la copia como WRITTEN. */
  public void reportStored(String blockId, long bytes, String sha256) {
    ReportBlockStoredRequest request = ReportBlockStoredRequest.newBuilder()
        .setDatanodeId(nodeId).setNodeToken(nodeToken)
        .setBlockId(blockId).setBytesWritten(bytes).setChecksumSha256(sha256)
        .build();
    controlNodes.call(ch -> NodeRegistryServiceGrpc.newBlockingStub(ch)
        .withDeadlineAfter(10, TimeUnit.SECONDS).reportBlockStored(request));
  }

  public void stop() {
    timer.shutdownNow();
    rabbit.close();
    controlNodes.close();
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
