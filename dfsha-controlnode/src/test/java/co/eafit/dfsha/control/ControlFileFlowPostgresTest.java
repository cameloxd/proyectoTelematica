package co.eafit.dfsha.control;

import static org.junit.jupiter.api.Assertions.*;

import co.eafit.dfsha.common.Env;
import co.eafit.dfsha.common.Tokens;
import co.eafit.dfsha.grpc.v1.AllocateFileResponse;
import co.eafit.dfsha.grpc.v1.BlockPlan;
import co.eafit.dfsha.grpc.v1.LoginResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * C2.1/C2.2 sobre el esquema vigente: PostgreSQL + servidor gRPC reales, sin mocks de repositorios.
 * Activar con DFSHA_TEST_DB_URL apuntando a una base de pruebas (permiso CREATE SCHEMA).
 * Cada caso crea y elimina exclusivamente su propio esquema dfsha_c2_<uuid>.
 */
@EnabledIfEnvironmentVariable(named = "DFSHA_TEST_DB_URL", matches = ".+")
@Timeout(30)
class ControlFileFlowPostgresTest extends PostgresControlFixture {
  @Test
  void twentyMiBSplitsIntoEightEightFourOnThreeNodesWithSignedTokens() throws Exception {
    node("a", 64 * MIB);
    node("b", 64 * MIB);
    node("c", 64 * MIB);
    var plan = allocate(alice, "/video.bin", 20 * MIB, "upload-1");
    assertEquals(BLOCK, plan.getBlockSizeBytes());
    assertEquals(List.of(8 * MIB, 8 * MIB, 4 * MIB), plan.getBlocksList().stream().map(BlockPlan::getSizeBytes).toList());
    assertEquals(List.of(0L, 8 * MIB, 16 * MIB), plan.getBlocksList().stream().map(BlockPlan::getOffsetBytes).toList());
    assertEquals(List.of(0, 1, 2), plan.getBlocksList().stream().map(BlockPlan::getBlockIndex).toList());
    assertEquals(3, plan.getBlocksList().stream().map(BlockPlan::getDatanodeId).distinct().count());
    assertEquals(20 * MIB, reserved());
    assertEquals(3, scalar("SELECT count(*) FROM block_location WHERE state = 'PLANNED' AND role = 'PRIMARY'"));
    for (var block : plan.getBlocksList()) {
      assertEquals(block.getBlockId(), tokens.verify(block.getWriteToken(), Tokens.WRITE));
      assertNull(tokens.verify(block.getWriteToken(), Tokens.READ));
      assertNull(tokens.verify(block.getWriteToken(), Tokens.SESSION));
    }
  }

  @ParameterizedTest
  @ValueSource(longs = {0, 8388608, 16777216, 16777217})
  void blockBoundariesHaveNoEmptyTrailingBlock(long size) throws Exception {
    node("a", 64 * MIB);
    var plan = allocate(alice, "/boundary", size, "boundary");
    assertEquals((size + BLOCK - 1) / BLOCK, plan.getBlocksCount());
    assertEquals(size, plan.getBlocksList().stream().mapToLong(BlockPlan::getSizeBytes).sum());
    assertTrue(plan.getBlocksList().stream().allMatch(b -> b.getSizeBytes() > 0 && b.getSizeBytes() <= BLOCK));
    assertEquals(size, reserved());
  }

  @Test
  void deadSuspectAndOverloadedNodesAreNotCandidates() throws Exception {
    node("dead", 64 * MIB);
    node("suspect", 64 * MIB);
    node("busy", 64 * MIB);
    var good = node("good", 64 * MIB);
    update("UPDATE datanode SET status = 'DEAD' WHERE node_name = 'dead'");
    update("UPDATE datanode SET status = 'SUSPECT' WHERE node_name = 'suspect'");
    update("UPDATE datanode SET active_transfers = ? WHERE node_name = 'busy'", Env.getInt("MAX_TRANSFERS_PER_NODE", 8));
    var plan = allocate(alice, "/only-good", BLOCK, "healthy");
    assertEquals(good.getDatanodeId(), plan.getBlocks(0).getDatanodeId());
  }

  @Test
  void noAliveNodesRollsBackTheWholeAllocation() throws Exception {
    fails(Status.Code.RESOURCE_EXHAUSTED, () -> allocate(alice, "/no-nodes", 1, "no-nodes"));
    assertNoAllocationRows();
  }

  @Test
  void runningOutOfSpaceAfterFirstBlockRollsBackAllRowsAndReservations() throws Exception {
    node("small", BLOCK);
    fails(Status.Code.RESOURCE_EXHAUSTED, () -> allocate(alice, "/too-large", 2 * BLOCK, "full"));
    assertNoAllocationRows();
    assertEquals(0, reserved());
  }

  @Test
  void sequentialRetryKeepsSameFileBlocksAndReservation() throws Exception {
    node("a", 64 * MIB);
    var first = allocate(alice, "/retry", 20 * MIB, "same-operation");
    var retry = allocate(alice, "/retry", 20 * MIB, "same-operation");
    assertEquals(first.getFile(), retry.getFile());
    assertEquals(blockIds(first), blockIds(retry));
    assertEquals(1, scalar("SELECT count(*) FROM dfs_file"));
    assertEquals(20 * MIB, reserved());
    fails(Status.Code.FAILED_PRECONDITION, () -> allocate(alice, "/other", 20 * MIB, "same-operation"));
    fails(Status.Code.FAILED_PRECONDITION, () -> allocate(alice, "/retry", 1, "same-operation"));
    assertEquals(20 * MIB, reserved());
  }

  @Test
  void pendingFilesAreHiddenAndCommitNamesExactlyTheMissingBlocks() throws Exception {
    node("a", 64 * MIB);
    var plan = allocate(alice, "/pending", 20 * MIB, "pending");
    assertEquals(0, as(alice).listDirectory(path("/")).getEntriesCount());
    fails(Status.Code.NOT_FOUND, () -> manifest(alice, "/pending"));
    var emptyCommit = commit(alice, plan);
    assertFalse(emptyCommit.getStatus().getOk());
    assertEquals("MISSING_BLOCKS", emptyCommit.getStatus().getCode());
    assertEquals(blockIds(plan), emptyCommit.getMissingBlockIdsList());
    stored(plan.getBlocks(0));
    var partialCommit = commit(alice, plan);
    assertEquals(blockIds(plan).subList(1, 3), partialCommit.getMissingBlockIdsList());
    assertEquals("PENDING", text("SELECT status::text FROM dfs_file WHERE file_id = ?", id(plan.getFile().getFileId())));
    assertEquals(12 * MIB, reserved());
  }

  @Test
  void allReportsAllowIdempotentCommitAndAnOrderedReadableManifest() throws Exception {
    node("a", 64 * MIB);
    node("b", 64 * MIB);
    node("c", 64 * MIB);
    var plan = allocate(alice, "/complete", 20 * MIB, "complete");
    for (var block : plan.getBlocksList()) stored(block);
    assertEquals(0, reserved());
    assertTrue(commit(alice, plan).getStatus().getOk());
    assertTrue(commit(alice, plan).getStatus().getOk());
    var manifest = manifest(alice, "/complete");
    assertEquals(20 * MIB, manifest.getFileSizeBytes());
    assertEquals(blockIds(plan), manifest.getBlocksList().stream().map(BlockPlan::getBlockId).toList());
    assertEquals(List.of("/complete"), as(alice).listDirectory(path("/")).getEntriesList().stream().map(e -> e.getPath()).toList());
    for (var block : manifest.getBlocksList()) {
      assertEquals(CHECKSUM, block.getChecksumSha256());
      assertEquals(1, block.getReplicasCount());
      assertEquals(block.getDatanodeId(), block.getReplicas(0).getDatanodeId());
      assertEquals(block.getBlockId(), tokens.verify(block.getReadToken(), Tokens.READ));
      assertNull(tokens.verify(block.getReadToken(), Tokens.WRITE));
    }
    assertEquals(20 * MIB, scalar("SELECT COALESCE(SUM(b.size_bytes), 0) FROM block_location l "
        + "JOIN dfs_block b USING (block_id) WHERE l.state = 'WRITTEN'"));
  }

  @Test
  void emptyFileCommitsWithoutDataNodes() {
    var plan = allocate(alice, "/empty", 0, "empty");
    assertEquals(0, plan.getBlocksCount());
    assertTrue(commit(alice, plan).getStatus().getOk());
    assertEquals(0, manifest(alice, "/empty").getFileSizeBytes());
  }

  @Test
  void wrongTokenWrongNodeSizeAndChecksumLeaveNoPartialChanges() throws Exception {
    node("a", 64 * MIB);
    var other = node("b", 64 * MIB);
    var block = allocate(alice, "/invalid-reports", BLOCK, "invalid-reports").getBlocks(0);
    // La ronda determinista debe elegir a antes que b si ambos tienen igual puntaje.
    assertNotEquals(other.getDatanodeId(), block.getDatanodeId());
    var correct = report(block);
    fails(Status.Code.UNAUTHENTICATED, () -> registry.reportBlockStored(correct.toBuilder().setNodeToken("invalid").build()));
    fails(Status.Code.NOT_FOUND, () -> registry.reportBlockStored(correct.toBuilder()
        .setDatanodeId(other.getDatanodeId()).setNodeToken(other.getNodeToken()).build()));
    fails(Status.Code.FAILED_PRECONDITION, () -> registry.reportBlockStored(correct.toBuilder().setBytesWritten(BLOCK - 1).build()));
    fails(Status.Code.INVALID_ARGUMENT, () -> registry.reportBlockStored(correct.toBuilder().setChecksumSha256("not-a-sha256").build()));
    assertNull(text("SELECT checksum_sha256 FROM dfs_block WHERE block_id = ?", id(block.getBlockId())));
    assertEquals("PLANNED", text("SELECT state::text FROM block_location WHERE block_id = ?", id(block.getBlockId())));
    assertEquals(BLOCK, reserved());
  }

  @Test
  void repeatedReportDoesNotDoubleCountAndConflictingChecksumIsRejected() throws Exception {
    node("a", 64 * MIB);
    var block = allocate(alice, "/report-retry", BLOCK, "report-retry").getBlocks(0);
    stored(block);
    stored(block);
    assertEquals(1, scalar("SELECT count(*) FROM block_location WHERE state = 'WRITTEN'"));
    assertEquals(0, reserved());
    fails(Status.Code.FAILED_PRECONDITION, () -> registry.reportBlockStored(report(block).toBuilder()
        .setChecksumSha256("cd".repeat(32)).build()));
    assertEquals(CHECKSUM, text("SELECT checksum_sha256 FROM dfs_block WHERE block_id = ?", id(block.getBlockId())));
  }

  @Test
  void anotherOwnerCannotCommitOrReadButCanUseSamePathAndOperation() throws Exception {
    node("a", 64 * MIB);
    var a = allocate(alice, "/private", BLOCK, "private");
    stored(a.getBlocks(0));
    fails(Status.Code.NOT_FOUND, () -> commit(bob, a));
    assertTrue(commit(alice, a).getStatus().getOk());
    fails(Status.Code.NOT_FOUND, () -> manifest(bob, "/private"));
    var b = allocate(bob, "/private", BLOCK, "private");
    assertNotEquals(a.getFile().getFileId(), b.getFile().getFileId());
    assertEquals(BLOCK, reserved());
  }

  @Test
  void allocationRequiresAuthenticationAndAnExistingParent() throws Exception {
    node("a", 64 * MIB);
    fails(Status.Code.UNAUTHENTICATED, () -> publicControl.withDeadlineAfter(10, TimeUnit.SECONDS)
        .allocateFile(request("/no-auth", 1, "no-auth")));
    fails(Status.Code.NOT_FOUND, () -> allocate(alice, "/missing/child", 1, "missing"));
    fails(Status.Code.INVALID_ARGUMENT, () -> allocate(alice, "/invalid", -1, "negative"));
    assertNoAllocationRows();
  }

  @Test
  void reregisteringNodePreservesItsIdentityAndAllocationButRotatesItsToken() throws Exception {
    var first = node("stable", 64 * MIB);
    var block = allocate(alice, "/restart", BLOCK, "restart").getBlocks(0);
    var oldReport = report(block);
    var again = node("stable", 64 * MIB);
    assertEquals(first.getDatanodeId(), again.getDatanodeId());
    assertNotEquals(first.getNodeToken(), again.getNodeToken());
    assertEquals(BLOCK, reserved());
    fails(Status.Code.UNAUTHENTICATED, () -> registry.reportBlockStored(oldReport));
    stored(block);
  }

  @Test
  void supersedingPendingUploadDoesNotLetLateReportOrCommitResurrectIt() throws Exception {
    node("a", 64 * MIB);
    var old = allocate(alice, "/replace", BLOCK, "old");
    var current = allocate(alice, "/replace", BLOCK, "new");
    fails(Status.Code.NOT_FOUND, () -> registry.reportBlockStored(report(old.getBlocks(0))));
    fails(Status.Code.NOT_FOUND, () -> commit(alice, old));
    assertEquals("DELETED", text("SELECT status::text FROM dfs_file WHERE file_id = ?", id(old.getFile().getFileId())));
    assertEquals("PENDING", text("SELECT status::text FROM dfs_file WHERE file_id = ?", id(current.getFile().getFileId())));
    assertEquals(BLOCK, reserved());
  }

  @Test
  void retryOfSupersededOperationMustNotReturnADeletedUploadPlan() {
    node("a", 64 * MIB);
    allocate(alice, "/replace", BLOCK, "old");
    allocate(alice, "/replace", BLOCK, "new");
    fails(Status.Code.FAILED_PRECONDITION, () -> allocate(alice, "/replace", BLOCK, "old"));
  }

  @Test
  void failedReplacementKeepsOriginalUploadAndReservationRetryable() throws Exception {
    node("a", BLOCK);
    var original = allocate(alice, "/replace", BLOCK, "original");
    fails(Status.Code.RESOURCE_EXHAUSTED, () -> allocate(alice, "/replace", 2 * BLOCK, "too-big"));
    var retry = allocate(alice, "/replace", BLOCK, "original");
    assertEquals(original.getFile(), retry.getFile());
    assertEquals(blockIds(original), blockIds(retry));
    assertEquals("PENDING", text("SELECT status::text FROM dfs_file WHERE file_id = ?", id(original.getFile().getFileId())));
    assertEquals(1, scalar("SELECT count(*) FROM dfs_file"));
    assertEquals(BLOCK, reserved());
    stored(original.getBlocks(0));
    assertTrue(commit(alice, original).getStatus().getOk());
  }

  @Test
  void retryOfCommittedOperationStillReturnsOriginalPlanWithoutReservingAgain() throws Exception {
    node("a", 64 * MIB);
    var original = allocate(alice, "/committed-retry", BLOCK, "committed-retry");
    stored(original.getBlocks(0));
    assertTrue(commit(alice, original).getStatus().getOk());
    var retry = allocate(alice, "/committed-retry", BLOCK, "committed-retry");
    assertEquals(original.getFile(), retry.getFile());
    assertEquals(blockIds(original), blockIds(retry));
    assertEquals(0, reserved());
    assertEquals("COMMITTED", text("SELECT status::text FROM dfs_file WHERE file_id = ?", id(original.getFile().getFileId())));
  }

  @Test
  void pendingFileAndDirectoryCannotOccupyTheSamePath() {
    node("a", 64 * MIB);
    allocate(alice, "/collision", BLOCK, "collision");
    fails(Status.Code.ALREADY_EXISTS, () -> as(alice).createDirectory(path("/collision")));
  }

  @Test
  void deletedFileOperationIsRejectedButItsPathCanBeReused() throws Exception {
    // Archivo vacío: verifica el borrado lógico sin necesitar publicar tareas RabbitMQ.
    var old = allocate(alice, "/reuse", 0, "deleted");
    assertTrue(commit(alice, old).getStatus().getOk());
    assertTrue(as(alice).deletePath(path("/reuse")).getOk());
    fails(Status.Code.FAILED_PRECONDITION, () -> allocate(alice, "/reuse", 0, "deleted"));
    assertTrue(as(alice).createDirectory(path("/reuse")).getOk());
    assertTrue(as(alice).deletePath(path("/reuse")).getOk());
    var current = allocate(alice, "/reuse", 0, "new");
    assertNotEquals(old.getFile().getFileId(), current.getFile().getFileId());
    assertTrue(commit(alice, current).getStatus().getOk());
    assertEquals("DELETED", text("SELECT status::text FROM dfs_file WHERE file_id = ?", id(old.getFile().getFileId())));
    assertEquals(0, reserved());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void concurrentDirectoryAndUploadCannotSharePath(boolean directoryStartsFirst) throws Exception {
    node("a", 64 * MIB);
    var workers = Executors.newFixedThreadPool(2);
    Callable<Status.Code> mkdir = () -> {
      try {
        assertTrue(as(alice).createDirectory(path("/race")).getOk());
        return null;
      } catch (StatusRuntimeException e) {
        return e.getStatus().getCode();
      }
    };
    try (Connection blocker = adminConnection()) {
      blocker.setSchema(schema);
      blocker.setAutoCommit(false);
      Db.one(blocker, "SELECT user_id FROM app_user WHERE user_id = ? FOR UPDATE",
          r -> r.getString(1), id(alice.getUserId()));
      Future<Status.Code> directory;
      Future<Result> upload;
      boolean firstQueued;
      boolean bothQueued;
      try {
        if (directoryStartsFirst) {
          directory = workers.submit(mkdir);
          firstQueued = waitForNamespaceWriters(1);
          upload = workers.submit(atOnce(new CountDownLatch(0), "/race", "race"));
        } else {
          upload = workers.submit(atOnce(new CountDownLatch(0), "/race", "race"));
          firstQueued = waitForNamespaceWriters(1);
          directory = workers.submit(mkdir);
        }
        bothQueued = waitForNamespaceWriters(2);
      } finally {
        blocker.commit();
      }
      assertTrue(firstQueued && bothQueued, "ambos escritores deben estar en vuelo antes de liberar el namespace");
      Status.Code directoryError = directory.get(15, TimeUnit.SECONDS);
      Result uploadResult = upload.get(15, TimeUnit.SECONDS);
      assertEquals(1, (directoryError == null ? 1 : 0) + (uploadResult.plan() != null ? 1 : 0));
      assertEquals(Status.Code.ALREADY_EXISTS, directoryError == null ? uploadResult.error() : directoryError);
      long dirs = scalar("SELECT count(*) FROM dfs_directory WHERE owner_id = ? AND path = '/race' AND deleted_at IS NULL",
          id(alice.getUserId()));
      long files = scalar("SELECT count(*) FROM dfs_file WHERE owner_id = ? AND path = '/race' AND status <> 'DELETED'",
          id(alice.getUserId()));
      assertEquals(1, dirs + files);
      assertEquals(uploadResult.plan() == null ? 0 : BLOCK, reserved());
    } finally {
      workers.shutdownNow();
    }
  }

  @Test
  void concurrentAllocationsCannotReserveMoreThanNodeCapacity() throws Exception {
    node("a", BLOCK);
    var start = new CountDownLatch(1);
    var workers = Executors.newFixedThreadPool(2);
    try {
      var a = workers.submit(atOnce(start, "/a", "capacity-a"));
      var b = workers.submit(atOnce(bob, start, "/b", "capacity-b"));
      start.countDown();
      var results = List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
      assertEquals(1, results.stream().filter(r -> r.plan() != null).count());
      assertEquals(List.of(Status.Code.RESOURCE_EXHAUSTED), results.stream()
          .filter(r -> r.error() != null).map(Result::error).toList());
      assertEquals(1, scalar("SELECT count(*) FROM dfs_file"));
      assertEquals(BLOCK, reserved());
    } finally {
      workers.shutdownNow();
    }
  }

  @Test
  void concurrentSameOperationMustReturnTheSamePlanToBothClients() throws Exception {
    node("a", 64 * MIB);
    var workers = Executors.newFixedThreadPool(2);
    var start = new CountDownLatch(1);
    try (Connection blocker = adminConnection()) {
      blocker.setAutoCommit(false);
      try (var s = blocker.createStatement()) {
        s.execute("SELECT pg_advisory_xact_lock(1)");
      }
      var a = workers.submit(atOnce(start, "/same", "same"));
      var b = workers.submit(atOnce(start, "/same", "same"));
      start.countDown();
      boolean bothQueued;
      try {
        bothQueued = waitForTwoAllocators();
      } finally {
        // Libera siempre la barrera, incluso si falla la comprobación de sincronización.
        blocker.commit();
      }
      assertTrue(bothQueued, "ambas peticiones deben esperar el lock que serializa la asignación");
      Result first = a.get(15, TimeUnit.SECONDS);
      Result second = b.get(15, TimeUnit.SECONDS);
      assertNull(first.error(), "el primer reintento no debe fallar");
      assertNull(second.error(), "el segundo reintento debe recibir el mismo plan, no ALREADY_EXISTS");
      assertEquals(first.plan().getFile(), second.plan().getFile());
      assertEquals(blockIds(first.plan()), blockIds(second.plan()));
      assertEquals(BLOCK, reserved());
    } finally {
      workers.shutdownNow();
    }
  }

  private boolean waitForTwoAllocators() throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      if (scalar("SELECT count(*) FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid "
          + "WHERE l.locktype = 'advisory' AND l.objid = 1 AND NOT l.granted AND a.application_name = ?", schema) >= 2) {
        return true;
      }
      Thread.sleep(25);
    }
    return false;
  }

  private boolean waitForNamespaceWriters(int count) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      if (scalar("SELECT count(*) FROM pg_stat_activity WHERE application_name = ? AND wait_event_type = 'Lock'", schema) >= count) {
        return true;
      }
      Thread.sleep(25);
    }
    return false;
  }

  private Callable<Result> atOnce(CountDownLatch start, String path, String operation) {
    return atOnce(alice, start, path, operation);
  }

  private Callable<Result> atOnce(LoginResponse owner, CountDownLatch start, String path, String operation) {
    return () -> {
      assertTrue(start.await(5, TimeUnit.SECONDS));
      try {
        return new Result(allocate(owner, path, BLOCK, operation), null);
      } catch (StatusRuntimeException e) {
        return new Result(null, e.getStatus().getCode());
      }
    };
  }

  private static List<String> blockIds(AllocateFileResponse plan) {
    return plan.getBlocksList().stream().map(BlockPlan::getBlockId).toList();
  }

  private void assertNoAllocationRows() throws Exception {
    assertEquals(0, scalar("SELECT count(*) FROM dfs_file"));
    assertEquals(0, scalar("SELECT count(*) FROM dfs_block"));
    assertEquals(0, scalar("SELECT count(*) FROM block_location"));
  }

  private record Result(AllocateFileResponse plan, Status.Code error) { }
}
