package co.eafit.dfsha.control;

import static org.junit.jupiter.api.Assertions.*;

import co.eafit.dfsha.grpc.v1.AllocateFileResponse;
import co.eafit.dfsha.grpc.v1.BlockDeleteTask;
import co.eafit.dfsha.grpc.v1.LockRequest;
import io.grpc.Status;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** PostgreSQL y RPC reales; el transporte inyectado simula confirmaciones y fallos del broker. */
@EnabledIfEnvironmentVariable(named = "DFSHA_TEST_DB_URL", matches = ".+")
@Timeout(30)
class DeleteOutboxPostgresTest extends PostgresControlFixture {
  @Test
  void deleteCommitsItsTasksWithoutAnyRabbitConnection() throws Exception {
    var plan = file("/deleted");
    var replica = node("replica", BLOCK);
    update("INSERT INTO block_location(block_id, datanode_id, role) VALUES (?, ?, 'REPLICA')",
        id(plan.getBlocks(0).getBlockId()), id(replica.getDatanodeId()));
    assertTrue(as(alice).deletePath(path("/deleted")).getOk());
    assertEquals("DELETED", text("SELECT status::text FROM dfs_file"));
    assertEquals(2, pending());
    assertEquals(2, scalar("SELECT count(*) FROM block_location WHERE state = 'DELETED'"));
    assertEquals(0, as(alice).listDirectory(path("/")).getEntriesCount());
    fails(Status.Code.NOT_FOUND, () -> manifest(alice, "/deleted"));
    fails(Status.Code.NOT_FOUND, () -> as(alice).deletePath(path("/deleted")));
    assertEquals(2, pending(), "un reintento no duplica tareas");
  }

  @Test
  void outboxInsertFailureRollsBackFileAndLocationsTogether() throws Exception {
    file("/atomic");
    update("""
        CREATE FUNCTION reject_delete_task() RETURNS trigger AS $$
        BEGIN RAISE EXCEPTION 'fallo SQL simulado'; END;
        $$ LANGUAGE plpgsql;
        CREATE TRIGGER reject_delete_task BEFORE INSERT ON block_delete_outbox
          FOR EACH ROW EXECUTE FUNCTION reject_delete_task()""");
    fails(Status.Code.INTERNAL, () -> as(alice).deletePath(path("/atomic")));
    assertEquals("COMMITTED", text("SELECT status::text FROM dfs_file"));
    assertEquals("WRITTEN", text("SELECT state::text FROM block_location"));
    assertEquals(0, pending());
    assertEquals(BLOCK, manifest(alice, "/atomic").getFileSizeBytes());
  }

  @Test
  void locksAndOtherOwnersCannotCreateDeletionTasks() throws Exception {
    file("/locked");
    fails(Status.Code.NOT_FOUND, () -> as(bob).deletePath(path("/locked")));
    assertTrue(as(alice).acquireLock(LockRequest.newBuilder().setPath("/locked").build()).getStatus().getOk());
    fails(Status.Code.FAILED_PRECONDITION, () -> as(alice).deletePath(path("/locked")));
    assertEquals(0, pending());
    assertEquals("COMMITTED", text("SELECT status::text FROM dfs_file"));
  }

  @Test
  void emptyFilesAndDirectoriesNeedNoPhysicalTasks() throws Exception {
    var empty = allocate(alice, "/empty", 0, "empty");
    assertTrue(commit(alice, empty).getStatus().getOk());
    assertTrue(as(alice).deletePath(path("/empty")).getOk());
    assertTrue(as(alice).createDirectory(path("/dir")).getOk());
    assertTrue(as(alice).deletePath(path("/dir")).getOk());
    assertEquals(0, pending());
  }

  @Test
  void brokerFailureSurvivesWorkerRestartAndRespectsRetryDelay() throws Exception {
    var plan = file("/retry");
    assertTrue(as(alice).deletePath(path("/retry")).getOk());
    var unavailable = new DeleteOutbox(db, (node, body) -> { throw new IOException("broker unavailable"); }, 60);
    try {
      assertEquals(DeleteOutbox.Result.RETRY, unavailable.publishOne());
      assertEquals(DeleteOutbox.Result.EMPTY, unavailable.publishOne(), "no reintentar antes del plazo");
    } finally { unavailable.stop(); }
    assertEquals(1, pending());
    assertEquals(1, scalar("SELECT attempts FROM block_delete_outbox"));
    assertEquals("IOException", text("SELECT last_error FROM block_delete_outbox"));
    update("UPDATE block_delete_outbox SET next_attempt_at = now() - interval '1 second'");
    List<String> sent = new ArrayList<>();
    var restarted = new DeleteOutbox(db, (node, body) -> {
      assertEquals(plan.getBlocks(0).getDatanodeId(), node);
      sent.add(BlockDeleteTask.parseFrom(body).getBlockId());
    }, 60);
    try {
      assertEquals(DeleteOutbox.Result.PUBLISHED, restarted.publishOne());
      assertEquals(DeleteOutbox.Result.EMPTY, restarted.publishOne());
    } finally { restarted.stop(); }
    assertEquals(List.of(plan.getBlocks(0).getBlockId()), sent);
    assertEquals(0, pending());
    assertEquals(2, scalar("SELECT attempts FROM block_delete_outbox"));
    assertNull(text("SELECT last_error FROM block_delete_outbox"));
  }

  @Test
  void workersSkipLockedTasksButContinueOtherNodes() throws Exception {
    var plan = file("/concurrent");
    var second = node("second", BLOCK);
    update("INSERT INTO block_location(block_id, datanode_id, role) VALUES (?, ?, 'REPLICA')",
        id(plan.getBlocks(0).getBlockId()), id(second.getDatanodeId()));
    assertTrue(as(alice).deletePath(path("/concurrent")).getOk());
    var publishing = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    List<String> firstSent = new ArrayList<>();
    List<String> secondSent = new ArrayList<>();
    var first = new DeleteOutbox(db, (node, body) -> {
      firstSent.add(node);
      publishing.countDown();
      assertTrue(release.await(10, TimeUnit.SECONDS));
    }, 10);
    var other = new DeleteOutbox(db, (node, body) -> secondSent.add(node), 10);
    var executor = Executors.newSingleThreadExecutor();
    try {
      var result = executor.submit(first::publishOne);
      assertTrue(publishing.await(5, TimeUnit.SECONDS));
      assertEquals(DeleteOutbox.Result.PUBLISHED, other.publishOne());
      assertEquals(DeleteOutbox.Result.EMPTY, other.publishOne(), "no tomar la fila del primer trabajador");
      release.countDown();
      assertEquals(DeleteOutbox.Result.PUBLISHED, result.get(10, TimeUnit.SECONDS));
      assertEquals(1, firstSent.size());
      assertEquals(1, secondSent.size());
      assertNotEquals(firstSent.get(0), secondSent.get(0));
      assertEquals(0, pending());
    } finally {
      release.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
      first.stop(); other.stop();
    }
  }

  @Test
  void dbFailureAfterPublishAllowsDuplicateDeliveryInsteadOfLosingTheTask() throws Exception {
    var plan = file("/ambiguous");
    assertTrue(as(alice).deletePath(path("/ambiguous")).getOk());
    update("""
        CREATE FUNCTION reject_publish_mark() RETURNS trigger AS $$
        BEGIN RAISE EXCEPTION 'fallo tras confirmación del broker'; END;
        $$ LANGUAGE plpgsql;
        CREATE TRIGGER reject_publish_mark BEFORE UPDATE OF published_at ON block_delete_outbox
          FOR EACH ROW EXECUTE FUNCTION reject_publish_mark()""");
    List<String> delivered = new ArrayList<>();
    var worker = new DeleteOutbox(db, (node, body) -> delivered.add(BlockDeleteTask.parseFrom(body).getBlockId()), 10);
    try {
      assertThrows(SQLException.class, worker::publishOne);
      assertEquals(1, pending());
      assertEquals(0, scalar("SELECT attempts FROM block_delete_outbox"));
      update("DROP TRIGGER reject_publish_mark ON block_delete_outbox");
      assertEquals(DeleteOutbox.Result.PUBLISHED, worker.publishOne());
    } finally { worker.stop(); }
    assertEquals(List.of(plan.getBlocks(0).getBlockId(), plan.getBlocks(0).getBlockId()), delivered);
    assertEquals(0, pending());
  }

  @Test
  void uncommittedDeleteCannotBePublishedAndRollbackLeavesNoTask() throws Exception {
    var plan = file("/uncommitted");
    var worker = new DeleteOutbox(db, (node, body) -> fail("no publicar una transacción sin confirmar"), 10);
    try (Connection c = adminConnection()) {
      c.setSchema(schema);
      c.setAutoCommit(false);
      Db.update(c, "UPDATE dfs_file SET status = 'DELETED', deleted_at = now() WHERE file_id = ?", id(plan.getFile().getFileId()));
      assertEquals(1, DeleteOutbox.enqueueFile(c, id(plan.getFile().getFileId())));
      assertEquals(DeleteOutbox.Result.EMPTY, worker.publishOne());
      c.rollback();
      assertEquals(0, pending());
      assertEquals("COMMITTED", text("SELECT status::text FROM dfs_file"));
      assertEquals("WRITTEN", text("SELECT state::text FROM block_location"));
    } finally { worker.stop(); }
  }

  @Test
  void migrationRecoversLegacyDeletedCopiesAndIsSafeToRepeat() throws Exception {
    var old = file("/legacy");
    // Emula un borrado de la versión anterior que nunca publicó en RabbitMQ.
    update("UPDATE dfs_file SET status = 'DELETED', deleted_at = now() WHERE file_id = ?", id(old.getFile().getFileId()));
    update("UPDATE block_location SET state = 'DELETED' WHERE block_id = ?", id(old.getBlocks(0).getBlockId()));
    var live = allocate(alice, "/still-live", 1, "live");
    migrate(); migrate();
    assertEquals(1, pending());
    assertEquals(old.getBlocks(0).getBlockId(), text("SELECT block_id::text FROM block_delete_outbox"));
    assertEquals("PENDING", text("SELECT status::text FROM dfs_file WHERE file_id = ?", id(live.getFile().getFileId())));
    var worker = new DeleteOutbox(db, (node, body) -> { }, 10);
    try { assertEquals(DeleteOutbox.Result.PUBLISHED, worker.publishOne()); }
    finally { worker.stop(); }
    migrate();
    assertEquals(0, pending(), "la migración no reabre tareas ya confirmadas");
    assertEquals(1, scalar("SELECT count(*) FROM block_delete_outbox"));
    assertEquals(1, scalar("SELECT attempts FROM block_delete_outbox"));
  }

  @Test
  void pendingUploadDeletionTargetsOldIdsNotAReusedPath() throws Exception {
    node("a", 64 * MIB);
    var old = allocate(alice, "/reuse", 1, "old");
    assertTrue(as(alice).deletePath(path("/reuse")).getOk());
    var current = allocate(alice, "/reuse", 1, "current");
    fails(Status.Code.NOT_FOUND, () -> stored(old.getBlocks(0)));
    assertEquals(old.getBlocks(0).getBlockId(), text("SELECT block_id::text FROM block_delete_outbox"));
    assertNotEquals(current.getBlocks(0).getBlockId(), text("SELECT block_id::text FROM block_delete_outbox"));
    stored(current.getBlocks(0));
    assertTrue(commit(alice, current).getStatus().getOk());
  }

  @Test
  void backgroundWorkerPublishesAndStops() throws Exception {
    file("/scheduled");
    assertTrue(as(alice).deletePath(path("/scheduled")).getOk());
    var sent = new AtomicInteger();
    var worker = new DeleteOutbox(db, (node, body) -> sent.incrementAndGet(), 10);
    try {
      worker.start();
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (pending() != 0 && System.nanoTime() < until) Thread.sleep(25);
      assertEquals(0, pending());
      assertEquals(1, sent.get());
    } finally { worker.stop(); }
  }

  @Test
  void missingMigrationPreventsWorkerStartup() throws Exception {
    update("DROP TABLE block_delete_outbox"); // solo el esquema aislado de ESTA prueba
    var worker = new DeleteOutbox(db, (node, body) -> fail("no publicar sin outbox"), 10);
    try { assertThrows(SQLException.class, worker::start); }
    finally { worker.stop(); }
  }

  @Test
  void failedTaskDoesNotBlockOtherDueTasks() throws Exception {
    var plan = file("/one-fails");
    var second = node("second", BLOCK);
    update("INSERT INTO block_location(block_id, datanode_id, role) VALUES (?, ?, 'REPLICA')",
        id(plan.getBlocks(0).getBlockId()), id(second.getDatanodeId()));
    assertTrue(as(alice).deletePath(path("/one-fails")).getOk());
    var calls = new AtomicInteger();
    var worker = new DeleteOutbox(db, (node, body) -> {
      if (calls.incrementAndGet() == 1) throw new IOException("fallo transitorio");
    }, 60);
    try {
      assertEquals(DeleteOutbox.Result.RETRY, worker.publishOne());
      assertEquals(DeleteOutbox.Result.PUBLISHED, worker.publishOne());
      assertEquals(DeleteOutbox.Result.EMPTY, worker.publishOne());
    } finally { worker.stop(); }
    assertEquals(1, pending());
    assertEquals(1, scalar("SELECT count(*) FROM block_delete_outbox WHERE published_at IS NOT NULL"));
    assertEquals(2, calls.get());
  }

  private AllocateFileResponse file(String path) {
    node("a", 64 * MIB);
    var plan = allocate(alice, path, BLOCK, path);
    stored(plan.getBlocks(0));
    assertTrue(commit(alice, plan).getStatus().getOk());
    return plan;
  }

  private long pending() throws SQLException {
    return scalar("SELECT count(*) FROM block_delete_outbox WHERE published_at IS NULL");
  }

  private void migrate() throws Exception {
    update(Files.readString(Path.of("..", "deploy", "migrations", "001-block-delete-outbox.sql")));
  }
}
