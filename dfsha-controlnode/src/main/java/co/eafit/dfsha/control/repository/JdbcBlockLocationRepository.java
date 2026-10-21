package co.eafit.dfsha.control.repository;

import java.sql.*;
import java.util.UUID;
import javax.sql.DataSource;

/** Ubicaciones previstas y escritas; la reserva/liberación se implementará de forma transaccional en C2. */
public final class JdbcBlockLocationRepository {
  private final DataSource dataSource;
  public JdbcBlockLocationRepository(DataSource dataSource) { this.dataSource=dataSource; }
  public void planPrimary(UUID blockId, UUID dataNodeId, long reservedBytes) throws SQLException {
    String sql="INSERT INTO block_location (block_id,datanode_id,role,state,reserved_bytes) VALUES (?,?,'PRIMARY','PLANNED',?)";
    try(Connection c=dataSource.getConnection();PreparedStatement s=c.prepareStatement(sql)){s.setObject(1,blockId);s.setObject(2,dataNodeId);s.setLong(3,reservedBytes);s.executeUpdate();}
  }
  public void markWritten(UUID blockId, UUID dataNodeId, String checksum) throws SQLException {
    try(Connection c=dataSource.getConnection();PreparedStatement s=c.prepareStatement("UPDATE block_location SET state='WRITTEN', reserved_bytes=0, written_at=now() WHERE block_id=? AND datanode_id=?")){
      s.setObject(1,blockId);s.setObject(2,dataNodeId);s.executeUpdate();
    }
    try(Connection c=dataSource.getConnection();PreparedStatement s=c.prepareStatement("UPDATE dfs_block SET checksum_sha256=? WHERE block_id=?")){s.setString(1,checksum);s.setObject(2,blockId);s.executeUpdate();}
  }
}
