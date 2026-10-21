package co.eafit.dfsha.control.repository;

import java.sql.*;
import java.util.*;
import javax.sql.DataSource;

public final class JdbcBlockRepository {
  private final DataSource dataSource;
  public JdbcBlockRepository(DataSource dataSource) { this.dataSource=dataSource; }
  public UUID create(UUID fileId,int index,long offset,long size) throws SQLException {
    try(Connection c=dataSource.getConnection(); PreparedStatement s=c.prepareStatement("INSERT INTO dfs_block (file_id,block_index,offset_bytes,size_bytes) VALUES (?,?,?,?) RETURNING block_id")) {
      s.setObject(1,fileId);s.setInt(2,index);s.setLong(3,offset);s.setLong(4,size);try(ResultSet r=s.executeQuery()){r.next();return r.getObject(1,UUID.class);}
    }
  }
  public List<Block> listForFile(UUID fileId) throws SQLException {
    List<Block> blocks=new ArrayList<>();try(Connection c=dataSource.getConnection();PreparedStatement s=c.prepareStatement("SELECT block_id,block_index,offset_bytes,size_bytes,checksum_sha256 FROM dfs_block WHERE file_id=? ORDER BY block_index")){
      s.setObject(1,fileId);try(ResultSet r=s.executeQuery()){while(r.next())blocks.add(new Block(r.getObject(1,UUID.class),r.getInt(2),r.getLong(3),r.getLong(4),r.getString(5)));}}return blocks;
  }
  public record Block(UUID id,int index,long offsetBytes,long sizeBytes,String checksumSha256) { }
}
