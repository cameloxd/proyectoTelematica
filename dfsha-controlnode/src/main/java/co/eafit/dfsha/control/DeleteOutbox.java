package co.eafit.dfsha.control;

import co.eafit.dfsha.common.Env;
import co.eafit.dfsha.common.Rabbit;
import co.eafit.dfsha.grpc.v1.BlockDeleteTask;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Borrados pendientes en PostgreSQL; publicación al menos una vez, nunca exactamente una vez. */
final class DeleteOutbox {
  private static final Logger log = LoggerFactory.getLogger(DeleteOutbox.class);
  private final Db db;
  private final Publisher publisher;
  private final long retrySeconds;
  private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();

  /** Solo termina normalmente si el broker confirmó una publicación enrutada y persistente. */
  @FunctionalInterface
  interface Publisher {
    void send(String nodeId, byte[] body) throws Exception;
  }

  DeleteOutbox(Db db, Rabbit rabbit) {
    this(db, rabbit::publishDelete, Env.getLong("DELETE_RETRY_SECONDS", 10));
  }

  // Inyectar únicamente el transporte permite probar fallos sin fingir PostgreSQL ni el RPC.
  DeleteOutbox(Db db, Publisher publisher, long retrySeconds) {
    if (retrySeconds < 1) throw new IllegalArgumentException("DELETE_RETRY_SECONDS debe ser positivo");
    this.db = db;
    this.publisher = publisher;
    this.retrySeconds = retrySeconds;
  }

  /** Debe ejecutarse dentro de la MISMA transacción que marca dfs_file como DELETED. */
  static int enqueueFile(Connection c, UUID fileId) throws SQLException {
    return Db.update(c, """
        WITH copies AS (
          UPDATE block_location l SET state = 'DELETED'
          FROM dfs_block b
          WHERE b.block_id = l.block_id AND b.file_id = ? AND l.state <> 'DELETED'
          RETURNING l.block_id, l.datanode_id
        )
        INSERT INTO block_delete_outbox(block_id, datanode_id)
        SELECT block_id, datanode_id FROM copies
        ON CONFLICT (block_id, datanode_id) DO NOTHING""", fileId);
  }

  void start() throws SQLException {
    // Fallar antes de atender RPC si una base existente no recibió la migración.
    db.tx(c -> Db.query(c, "SELECT block_id FROM block_delete_outbox LIMIT 0", r -> 1));
    timer.scheduleWithFixedDelay(this::publishPending, 0, 2, TimeUnit.SECONDS);
  }

  void stop() {
    timer.shutdownNow();
    try {
      if (!timer.awaitTermination(10, TimeUnit.SECONDS)) log.warn("Publicador de borrados aún terminando");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void publishPending() {
    try {
      // Una vuelta acotada; si el broker falla, esperar en lugar de agotar todos los reintentos.
      for (int i = 0; i < 100 && !Thread.currentThread().isInterrupted(); i++) {
        if (publishOne() != Result.PUBLISHED) break;
      }
    } catch (Exception e) {
      log.warn("No pude procesar la outbox de borrados: {}", e.getClass().getSimpleName());
    }
  }

  /** La fila queda bloqueada hasta confirmar: dos ControlNodes no envían la misma a la vez. */
  Result publishOne() throws SQLException {
    return db.tx(c -> {
      Task task = Db.one(c, """
          SELECT block_id, datanode_id FROM block_delete_outbox
          WHERE published_at IS NULL AND next_attempt_at <= now()
          ORDER BY next_attempt_at, created_at, block_id, datanode_id
          LIMIT 1 FOR UPDATE SKIP LOCKED""",
          r -> new Task(r.getObject(1, UUID.class), r.getObject(2, UUID.class)));
      if (task == null) return Result.EMPTY;
      try {
        publisher.send(task.nodeId().toString(),
            BlockDeleteTask.newBuilder().setBlockId(task.blockId().toString()).build().toByteArray());
      } catch (Exception e) {
        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        // Guardar solo la clase: mensajes de transporte pueden contener datos de conexión.
        Db.update(c, """
            UPDATE block_delete_outbox SET attempts = attempts + 1, last_error = ?,
              next_attempt_at = now() + ? * interval '1 second'
            WHERE block_id = ? AND datanode_id = ?""",
            e.getClass().getSimpleName(), retrySeconds, task.blockId(), task.nodeId());
        log.warn("Borrado de {} en {} pendiente; se reintentará", task.blockId(), task.nodeId());
        return Result.RETRY;
      }
      // Si el proceso cae aquí o falla el commit, otro trabajador reenviará la tarea.
      Db.update(c, """
          UPDATE block_delete_outbox SET attempts = attempts + 1, published_at = now(), last_error = NULL
          WHERE block_id = ? AND datanode_id = ?""", task.blockId(), task.nodeId());
      return Result.PUBLISHED;
    });
  }

  enum Result { EMPTY, PUBLISHED, RETRY }
  private record Task(UUID blockId, UUID nodeId) { }
}
