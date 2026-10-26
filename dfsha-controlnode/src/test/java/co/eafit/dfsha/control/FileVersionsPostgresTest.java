package co.eafit.dfsha.control;

import static org.junit.jupiter.api.Assertions.*;
import co.eafit.dfsha.grpc.v1.*;
import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "DFSHA_TEST_DB_URL", matches = ".+")
@Timeout(30)
class FileVersionsPostgresTest extends PostgresControlFixture {
  private AllocateFileResponse original() {
    node("a", 128 * MIB);
    var old = allocate(alice, "/file", BLOCK, "original");
    stored(old.getBlocks(0));
    assertTrue(commit(alice, old).getStatus().getOk());
    return old;
  }
  private LockResponse lease() { return as(alice).acquireLock(LockRequest.newBuilder().setPath("/file").setTtlSeconds(60).build()); }
  private AllocateFileRequest replacement(AllocateFileResponse old, LockResponse lock) {
    return request("/file", BLOCK, "replacement").toBuilder().setReplacesFileId(old.getFile().getFileId()).setLockToken(lock.getLockId()).build();
  }
  @Test void versionStaysVisibleUntilReplacementCommitsAtomically() throws Exception {
    var old = original(); var lock = lease(); var req = replacement(old, lock);
    var next = as(alice).allocateFile(req);
    assertEquals(old.getFile(), manifest(alice, "/file").getFile());
    assertFalse(commit(alice, next).getStatus().getOk());
    assertEquals(old.getFile(), manifest(alice, "/file").getFile());
    stored(next.getBlocks(0));
    assertTrue(commit(alice, next).getStatus().getOk());
    assertEquals(next.getFile(), manifest(alice, "/file").getFile());
    assertEquals(1, scalar("SELECT count(*) FROM block_delete_outbox WHERE block_id = ?", id(old.getBlocks(0).getBlockId())));
    as(alice).releaseLock(LockRef.newBuilder().setLockId(lock.getLockId()).build());
    assertTrue(commit(alice, next).getStatus().getOk());
    assertEquals(next.getFile(), as(alice).allocateFile(req).getFile()); // idempotente aun con lease liberado
  }
  @Test void wrongOrExpiredLeaseCannotReplaceOrCommit() throws Exception {
    var old = original(); var lock = lease();
    var req = replacement(old, lock);
    fails(Status.Code.FAILED_PRECONDITION, () -> as(alice).allocateFile(req.toBuilder().setLockToken(java.util.UUID.randomUUID().toString()).build()));
    var next = as(alice).allocateFile(req); stored(next.getBlocks(0));
    update("UPDATE file_lock SET expires_at = now() - interval '1 second'");
    fails(Status.Code.FAILED_PRECONDITION, () -> commit(alice, next));
    assertEquals(old.getFile(), manifest(alice, "/file").getFile());
  }
  @Test void anotherUserCannotReplaceAFileVersion() {
    var old = original(); var lock = lease();
    fails(Status.Code.FAILED_PRECONDITION, () -> as(bob).allocateFile(replacement(old, lock)));
  }
  @Test void abortedReplacementQueuesItsAlreadyWrittenBlocks() throws Exception {
    var old = original(); var lock = lease();
    var req = replacement(old, lock); var first = as(alice).allocateFile(req); stored(first.getBlocks(0));
    var second = as(alice).allocateFile(req.toBuilder().setOperationId("replacement-2").build());
    assertNotEquals(first.getFile(), second.getFile());
    assertEquals(1, scalar("SELECT count(*) FROM block_delete_outbox WHERE block_id = ?", id(first.getBlocks(0).getBlockId())));
    assertEquals(old.getFile(), manifest(alice, "/file").getFile());
    fails(Status.Code.NOT_FOUND, () -> commit(alice, first));
  }
  @Test void versionAndLeaseArePartOfIdempotenceIdentity() {
    var old = original(); var lock = lease(); var req = replacement(old, lock);
    as(alice).allocateFile(req);
    fails(Status.Code.FAILED_PRECONDITION, () -> as(alice).allocateFile(req.toBuilder().clearReplacesFileId().clearLockToken().build()));
  }
  @Test void deletingOriginalAlsoAbortsExpiredPendingReplacement() throws Exception {
    var old = original(); var lock = lease(); var next = as(alice).allocateFile(replacement(old, lock));
    stored(next.getBlocks(0));
    update("UPDATE file_lock SET expires_at = now() - interval '1 second'");
    as(alice).deletePath(path("/file"));
    assertEquals(2, scalar("SELECT count(*) FROM block_delete_outbox"));
    fails(Status.Code.NOT_FOUND, () -> commit(alice, next));
  }
  @Test void expiredPendingUploadQueuesCleanupWithoutDeletingCurrentVersion() throws Exception {
    var old = original(); var lock = lease(); var next = as(alice).allocateFile(replacement(old, lock));
    stored(next.getBlocks(0));
    update("UPDATE dfs_file SET created_at = now() - interval '2 hours' WHERE file_id = ?", id(next.getFile().getFileId()));
    var worker = new DeleteOutbox(db, (node, body) -> {}, 1);
    assertEquals(1, worker.expireAbandoned(3600));
    assertEquals(old.getFile(), manifest(alice, "/file").getFile());
    assertEquals(1, scalar("SELECT count(*) FROM block_delete_outbox WHERE block_id = ?", id(next.getBlocks(0).getBlockId())));
    assertEquals(0, worker.expireAbandoned(3600));
  }
}
