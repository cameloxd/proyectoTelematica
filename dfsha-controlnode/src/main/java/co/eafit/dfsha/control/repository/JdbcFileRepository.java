package co.eafit.dfsha.control.repository;

import java.sql.*;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** Repositorio de archivos PENDING/COMMITTED; las transacciones se coordinan en Fase 2. */
public final class JdbcFileRepository {
  private final DataSource dataSource;
  public JdbcFileRepository(DataSource dataSource) { this.dataSource = dataSource; }
  public UUID createPending(UUID ownerId, String path, long size, int blockSize, String operationId, Timestamp expiresAt) throws SQLException {
    String sql = "INSERT INTO dfs_file (owner_id,path,size_bytes,block_size_bytes,operation_id,pending_expires_at) VALUES (?,?,?,?,?,?) RETURNING file_id";
    try (Connection c=dataSource.getConnection(); PreparedStatement s=c.prepareStatement(sql)) {
      s.setObject(1,ownerId); s.setString(2,path); s.setLong(3,size); s.setInt(4,blockSize); s.setString(5,operationId); s.setTimestamp(6,expiresAt);
      try(ResultSet r=s.executeQuery()){r.next(); return r.getObject(1,UUID.class);}
    }
  }
  public Optional<FileRecord> findByOperation(UUID ownerId, String operationId) throws SQLException {
    String sql="SELECT file_id,path,size_bytes,block_size_bytes,status::text FROM dfs_file WHERE owner_id=? AND operation_id=?";
    try(Connection c=dataSource.getConnection(); PreparedStatement s=c.prepareStatement(sql)) { s.setObject(1,ownerId);s.setString(2,operationId);try(ResultSet r=s.executeQuery()){
      return r.next()?Optional.of(new FileRecord(r.getObject(1,UUID.class),r.getString(2),r.getLong(3),r.getInt(4),r.getString(5))):Optional.empty(); }}
  }
  public record FileRecord(UUID id,String path,long sizeBytes,int blockSizeBytes,String status) { }
}
