package co.eafit.dfsha.control;

import co.eafit.dfsha.common.Env;
import co.eafit.dfsha.common.Rabbit;
import co.eafit.dfsha.common.Tokens;
import co.eafit.dfsha.grpc.v1.Heartbeat;
import co.eafit.dfsha.grpc.v1.ReplicationTask;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Las tres tareas de fondo del ControlNode (alta disponibilidad y replicación):
 *
 * <ol>
 *   <li><b>Heartbeats</b> (RabbitMQ): cada latido actualiza el nodo y lo deja ALIVE.</li>
 *   <li><b>Estados</b> (cada 5 s): sin latido por SUSPECT_AFTER_SECONDS -&gt; SUSPECT; por
 *       DEAD_AFTER_SECONDS -&gt; DEAD.</li>
 *   <li><b>Réplicas</b> (cada REPLICATION_SCAN_SECONDS): busca bloques de archivos confirmados con
 *       menos copias vivas que REPLICATION_FACTOR y publica una ReplicationTask por cada copia que
 *       falta. El mismo escaneo sirve para las réplicas de un archivo nuevo y para reponer las
 *       copias de un DataNode que murió.</li>
 * </ol>
 *
 * Pueden correr dos ControlNodes a la vez: los heartbeats se reparten entre ambos y el escaneo de
 * réplicas usa un candado de PostgreSQL para que solo uno lo haga en cada vuelta.
 */
public final class ClusterMonitor {
  private static final Logger log = LoggerFactory.getLogger(ClusterMonitor.class);

  private final Db db;
  private final Tokens tokens;
  private final Rabbit rabbit;
  private final Placement placement = Placement.fromEnv();
  private final int replicationFactor = Env.getInt("REPLICATION_FACTOR", 3);
  private final long suspectAfter = Env.getLong("SUSPECT_AFTER_SECONDS", 15);
  private final long deadAfter = Env.getLong("DEAD_AFTER_SECONDS", 30);
  private final long scanSeconds = Env.getLong("REPLICATION_SCAN_SECONDS", 5);
  private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();

  public ClusterMonitor(Db db, Tokens tokens, Rabbit rabbit) {
    this.db = db;
    this.tokens = tokens;
    this.rabbit = rabbit;
  }

  public void start() throws Exception {
    rabbit.consume(Rabbit.HEARTBEAT_QUEUE, this::onHeartbeat);
    timer.scheduleWithFixedDelay(this::updateNodeStatus, 5, 5, TimeUnit.SECONDS);
    timer.scheduleWithFixedDelay(this::replicateMissingCopies, scanSeconds, scanSeconds, TimeUnit.SECONDS);
    log.info("Monitor activo: factor de replicación {}, SUSPECT a {} s, DEAD a {} s",
        replicationFactor, suspectAfter, deadAfter);
  }

  public void stop() {
    timer.shutdownNow();
  }

  // ------------------------------------------------------------------ 1. heartbeats

  private void onHeartbeat(byte[] body) throws Exception {
    Heartbeat hb = Heartbeat.parseFrom(body);
    String revived = db.tx(c -> {
      String previous = Db.one(c, "SELECT status::text FROM datanode WHERE datanode_id = ?",
          r -> r.getString(1), UUID.fromString(hb.getDatanodeId()));
      NodeRegistryImpl.requireNodeToken(c, UUID.fromString(hb.getDatanodeId()), hb.getNodeToken());
      Db.update(c, """
          UPDATE datanode SET status = 'ALIVE', last_heartbeat_at = now(),
                 capacity_bytes = ?, free_bytes = ?, active_transfers = ?
          WHERE datanode_id = ?""",
          hb.getCapacityBytes(), hb.getFreeBytes(), hb.getActiveTransfers(), UUID.fromString(hb.getDatanodeId()));
      return "ALIVE".equals(previous) ? null : previous;
    });
    if (revived != null) log.info("DataNode {} volvió: {} -> ALIVE", hb.getDatanodeId(), revived);
  }

  // ------------------------------------------------------------------ 2. ALIVE / SUSPECT / DEAD

  private void updateNodeStatus() {
    try {
      db.tx(c -> {
        for (String name : Db.query(c, """
            UPDATE datanode SET status = 'DEAD'
            WHERE status <> 'DEAD' AND last_heartbeat_at < now() - ? * interval '1 second'
            RETURNING node_name""", r -> r.getString(1), deadAfter)) {
          log.warn("DataNode {} -> DEAD (sin heartbeat hace más de {} s)", name, deadAfter);
        }
        for (String name : Db.query(c, """
            UPDATE datanode SET status = 'SUSPECT'
            WHERE status = 'ALIVE' AND last_heartbeat_at < now() - ? * interval '1 second'
            RETURNING node_name""", r -> r.getString(1), suspectAfter)) {
          log.warn("DataNode {} -> SUSPECT (sin heartbeat hace más de {} s)", name, suspectAfter);
        }
        return null;
      });
    } catch (Exception e) {
      log.error("No pude actualizar el estado de los nodos: {}", e.toString());
    }
  }

  // ------------------------------------------------------------------ 3. réplicas

  private void replicateMissingCopies() {
    try {
      List<ReplicationTask> tasks = db.tx(c -> {
        List<ReplicationTask> result = new ArrayList<>();
        // Solo un ControlNode escanea a la vez (si el otro lo está haciendo, esta vuelta se salta).
        if (!Db.one(c, "SELECT pg_try_advisory_xact_lock(2)", r -> r.getBoolean(1))) return result;

        // Una réplica pedida hace más de 60 s que nunca llegó (nodo caído, tarea fallida) se reintenta.
        Db.update(c, """
            DELETE FROM block_location
            WHERE role = 'REPLICA' AND state = 'PLANNED' AND created_at < now() - interval '60 seconds'""");

        // Copias que cuentan: las PLANNED (en camino) y las WRITTEN en nodos que no estén DEAD.
        List<Missing> missing = Db.query(c, """
            SELECT b.block_id, b.size_bytes, b.checksum_sha256,
                   COUNT(*) FILTER (WHERE l.state = 'PLANNED' OR d.status <> 'DEAD') AS copies
            FROM dfs_block b
            JOIN dfs_file f ON f.file_id = b.file_id AND f.status = 'COMMITTED'
            JOIN block_location l ON l.block_id = b.block_id AND l.state <> 'DELETED'
            JOIN datanode d ON d.datanode_id = l.datanode_id
            GROUP BY b.block_id
            HAVING COUNT(*) FILTER (WHERE l.state = 'PLANNED' OR d.status <> 'DEAD') < ?
            LIMIT 200""",
            r -> new Missing(r.getObject(1, UUID.class), r.getLong(2), r.getString(3), r.getInt(4)),
            replicationFactor);
        if (missing.isEmpty()) return result;

        List<Placement.Node> nodes = Placement.aliveNodes(c);
        for (Missing block : missing) {
          // Origen: una copia escrita en un nodo ALIVE (la menos cargada).
          Source source = Db.one(c, """
              SELECT d.datanode_id, d.internal_endpoint FROM block_location l
              JOIN datanode d ON d.datanode_id = l.datanode_id
              WHERE l.block_id = ? AND l.state = 'WRITTEN' AND d.status = 'ALIVE'
              ORDER BY d.active_transfers LIMIT 1""",
              r -> new Source(r.getString(1), r.getString(2)), block.id());
          if (source == null) continue;      // no hay ninguna copia viva de la cual copiar

          Set<UUID> alreadyHaveIt = new HashSet<>(Db.query(c,
              "SELECT datanode_id FROM block_location WHERE block_id = ? AND state <> 'DELETED'",
              r -> r.getObject(1, UUID.class), block.id()));
          for (Placement.Node target : placement.chooseReplicas(nodes, alreadyHaveIt, block.size(),
              replicationFactor - block.copies())) {
            // Si el nodo tuvo antes una copia borrada, la fila se reutiliza.
            Db.update(c, """
                INSERT INTO block_location(block_id, datanode_id, role) VALUES (?, ?, 'REPLICA')
                ON CONFLICT (block_id, datanode_id) DO UPDATE
                  SET role = 'REPLICA', state = 'PLANNED', created_at = now(), written_at = NULL""",
                block.id(), target.id);
            target.assign(block.size());
            result.add(ReplicationTask.newBuilder()
                .setBlockId(block.id().toString())
                .setSourceDatanodeId(source.nodeId()).setSourceEndpoint(source.endpoint())
                .setTargetDatanodeId(target.id.toString())
                .setChecksumSha256(block.checksum()).setSizeBytes(block.size())
                .setReadToken(tokens.issue(Tokens.READ, block.id().toString(), 600))
                .build());
          }
        }
        return result;
      });

      for (ReplicationTask task : tasks) {
        rabbit.declareNodeQueues(task.getTargetDatanodeId());
        rabbit.publish(Rabbit.replicationKey(task.getTargetDatanodeId()), task.toByteArray(), true);
        log.info("Réplica pedida: bloque {} de {} a {}", task.getBlockId(), task.getSourceDatanodeId(),
            task.getTargetDatanodeId());
      }
    } catch (Exception e) {
      log.error("Falló el escaneo de réplicas: {}", e.toString());
    }
  }

  private record Missing(UUID id, long size, String checksum, int copies) { }

  private record Source(String nodeId, String endpoint) { }
}
