package co.eafit.dfsha.control.repository;

import co.eafit.dfsha.control.model.DataNode;
import java.sql.*;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** Adaptador PostgreSQL para el catálogo de DataNodes. */
public final class JdbcDataNodeRepository implements DataNodeRepository {
  private static final String UPSERT = """
      INSERT INTO datanode (node_name, endpoint, internal_endpoint, node_token_hash, status, capacity_bytes, free_bytes)
      VALUES (?, ?, ?, ?, 'ALIVE', ?, ?)
      ON CONFLICT (node_name) DO UPDATE SET endpoint = EXCLUDED.endpoint,
        internal_endpoint = EXCLUDED.internal_endpoint, node_token_hash = EXCLUDED.node_token_hash,
        status = 'ALIVE',
        capacity_bytes = EXCLUDED.capacity_bytes, free_bytes = EXCLUDED.free_bytes,
        last_heartbeat_at = now()
      RETURNING datanode_id, node_name, endpoint, internal_endpoint, status::text,
        capacity_bytes, free_bytes, reserved_bytes, active_transfers, stored_blocks
      """;
  private final DataSource dataSource;
  public JdbcDataNodeRepository(DataSource dataSource) { this.dataSource = dataSource; }

  @Override public DataNode register(String name, String endpoint, String internalEndpoint, long capacity,
                                     String tokenHash) throws SQLException {
    try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(UPSERT)) {
      s.setString(1, name); s.setString(2, endpoint); s.setString(3, internalEndpoint);
      s.setString(4, tokenHash); s.setLong(5, capacity); s.setLong(6, capacity);
      try (ResultSet r = s.executeQuery()) { r.next(); return map(r); }
    }
  }

  @Override public Optional<DataNode> findByName(String name) throws SQLException {
    String sql = "SELECT datanode_id, node_name, endpoint, internal_endpoint, status::text, capacity_bytes, free_bytes, reserved_bytes, active_transfers, stored_blocks FROM datanode WHERE node_name = ?";
    try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
      s.setString(1, name); try (ResultSet r = s.executeQuery()) { return r.next() ? Optional.of(map(r)) : Optional.empty(); }
    }
  }

  private static DataNode map(ResultSet r) throws SQLException {
    return new DataNode(r.getObject("datanode_id", UUID.class), r.getString("node_name"),
        r.getString("endpoint"), r.getString("internal_endpoint"), r.getString("status"),
        r.getLong("capacity_bytes"), r.getLong("free_bytes"), r.getLong("reserved_bytes"),
        r.getInt("active_transfers"), r.getLong("stored_blocks"));
  }
}
