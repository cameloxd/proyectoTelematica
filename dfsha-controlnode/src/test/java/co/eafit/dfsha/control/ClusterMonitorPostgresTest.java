package co.eafit.dfsha.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.eafit.dfsha.common.Tokens;
import co.eafit.dfsha.grpc.v1.AllocateFileResponse;
import java.sql.Connection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Prueba el planificador real y sus reservas PostgreSQL, sin publicar en RabbitMQ. */
@EnabledIfEnvironmentVariable(named = "DFSHA_TEST_DB_URL", matches = ".+")
@Timeout(30)
class ClusterMonitorPostgresTest extends PostgresControlFixture {
  private ClusterMonitor monitor;

  @BeforeEach
  void createPlanner() {
    monitor = new ClusterMonitor(db, tokens, null);
  }

  @AfterEach
  void stopPlanner() {
    if (monitor != null) monitor.stop();
  }

  @Test
  void replicasReserveDistinctNodesAndRepeatedScanDoesNotDuplicateThePlan() throws Exception {
    node("a-source", BLOCK);
    var b = node("b-target", BLOCK);
    var c = node("c-target", BLOCK);
    var plan = committedBlock();

    var tasks = monitor.planReplication();
    assertEquals(2, tasks.size(), "esta batería usa REPLICATION_FACTOR=3");
    assertEquals(Set.of(b.getDatanodeId(), c.getDatanodeId()),
        tasks.stream().map(t -> t.getTargetDatanodeId()).collect(Collectors.toSet()));
    assertEquals(3, scalar("SELECT count(*) FROM block_location"));
    assertEquals(2 * BLOCK, reserved());
    for (var task : tasks) {
      assertEquals(plan.getBlocks(0).getBlockId(), task.getBlockId());
      assertEquals(plan.getBlocks(0).getDatanodeId(), task.getSourceDatanodeId());
      assertEquals(BLOCK, task.getSizeBytes());
      assertEquals(CHECKSUM, task.getChecksumSha256());
      assertEquals(task.getBlockId(), tokens.verify(task.getReadToken(), Tokens.READ));
      assertNull(tokens.verify(task.getReadToken(), Tokens.WRITE));
    }
    assertTrue(monitor.planReplication().isEmpty());
    assertEquals(3, scalar("SELECT count(*) FROM block_location"));
    assertEquals(2 * BLOCK, reserved());
  }

  @Test
  void replicaPlannerWaitsForPrimaryReservationAndRechecksAvailableSpace() throws Exception {
    node("a-source", BLOCK);
    var target = node("b-target", BLOCK);
    committedBlock();
    var workers = Executors.newSingleThreadExecutor();
    try (Connection primary = adminConnection()) {
      primary.setSchema(schema);
      primary.setAutoCommit(false);
      CapacityReservations.lock(primary);
      var scan = workers.submit(monitor::planReplication);
      try {
        assertTrue(waitForPlannerCapacityLock(), "el escáner debe esperar el mismo lock que AllocateFile");
        assertEquals(1, scalar("SELECT count(*) FROM block_location"), "no reservar réplicas antes del lock");
        // Simula la reserva que confirma una asignación primaria mientras tiene el candado.
        UUID file = Db.one(primary, """
            INSERT INTO dfs_file(owner_id, path, size_bytes, block_size_bytes, operation_id)
            VALUES (?, '/competing', ?, ?, 'competing') RETURNING file_id""",
            r -> r.getObject(1, UUID.class), id(alice.getUserId()), BLOCK, (int) BLOCK);
        UUID block = Db.one(primary, """
            INSERT INTO dfs_block(file_id, block_index, offset_bytes, size_bytes)
            VALUES (?, 0, 0, ?) RETURNING block_id""", r -> r.getObject(1, UUID.class), file, BLOCK);
        Db.update(primary, "INSERT INTO block_location(block_id, datanode_id, role) VALUES (?, ?, 'PRIMARY')",
            block, id(target.getDatanodeId()));
      } finally {
        primary.commit();       // libera siempre el candado de la transacción de prueba
      }
      assertTrue(scan.get(15, TimeUnit.SECONDS).isEmpty(), "el nodo ya no tiene espacio para la réplica");
      assertEquals(2, scalar("SELECT count(*) FROM block_location"));
      assertTrue(db.tx(Placement::aliveNodes).stream().allMatch(n -> n.available >= 0));
    } finally {
      workers.shutdownNow();
    }
  }

  private AllocateFileResponse committedBlock() {
    var plan = allocate(alice, "/source", BLOCK, "source");
    stored(plan.getBlocks(0));
    assertTrue(commit(alice, plan).getStatus().getOk());
    return plan;
  }

  private boolean waitForPlannerCapacityLock() throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      if (scalar("""
          SELECT count(*) FROM pg_stat_activity
          WHERE application_name = ? AND wait_event_type = 'Lock'
            AND query LIKE '%pg_advisory_xact_lock(1)%'""", schema) > 0) return true;
      Thread.sleep(25);
    }
    return false;
  }
}
