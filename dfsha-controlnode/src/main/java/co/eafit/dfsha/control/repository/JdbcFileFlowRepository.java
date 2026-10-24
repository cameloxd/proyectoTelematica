package co.eafit.dfsha.control.repository;

import co.eafit.dfsha.control.model.BlockPlacement;
import co.eafit.dfsha.control.model.CommitResult;
import co.eafit.dfsha.control.model.FilePlan;
import co.eafit.dfsha.control.service.AllocationPolicy;
import co.eafit.dfsha.control.service.AllocationPolicy.Candidate;
import co.eafit.dfsha.control.service.FileFlowException;
import co.eafit.dfsha.control.service.FileFlowException.Kind;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/** Transacciones completas del flujo de subida/commit/manifest. */
public final class JdbcFileFlowRepository implements FileFlowRepository {
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private final DataSource dataSource;
  private final AllocationPolicy policy;
  private final long reservedFloor;
  private final int maxTransfers;
  private final long targetBlocks;

  public JdbcFileFlowRepository(DataSource dataSource, AllocationPolicy policy,
                                long reservedFloor, int maxTransfers, long targetBlocks) {
    this.dataSource = dataSource; this.policy = policy; this.reservedFloor = reservedFloor;
    this.maxTransfers = maxTransfers; this.targetBlocks = targetBlocks;
  }

  @Override public FilePlan allocate(UUID ownerId, String path, long size, String operationId,
                                     int blockSize, long ttlSeconds) throws FileFlowException {
    validateAllocation(path, size, operationId, blockSize);
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false); c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
      try {
        FilePlan existing = findByOperation(c, ownerId, operationId);
        if (existing != null) {
          if (!existing.path().equals(path) || existing.sizeBytes() != size) {
            throw new FileFlowException(Kind.FAILED_PRECONDITION,
                "operation_id ya fue usado con otro archivo");
          }
          c.commit(); return existing;
        }
        requireParent(c, ownerId, parentOf(path));
        UUID fileId = insertPendingFile(c, ownerId, path, size, blockSize, operationId, ttlSeconds);
        List<Candidate> nodes = lockEligibleNodes(c);
        List<BlockPlacement> blocks = allocateBlocks(c, fileId, size, blockSize, nodes);
        c.commit(); return new FilePlan(fileId, path, size, blockSize, operationId, "PENDING", blocks);
      } catch (FileFlowException e) { rollback(c); throw e; }
      catch (SQLException e) {
        rollback(c);
        if ("23505".equals(e.getSQLState())) throw new FileFlowException(Kind.ALREADY_EXISTS,
            "el path o el operation_id ya está en uso", e);
        throw databaseFailure(e);
      }
    } catch (SQLException e) { throw databaseFailure(e); }
  }

  @Override public CommitResult commit(UUID ownerId, UUID fileId, String operationId)
      throws FileFlowException {
    if (operationId == null || operationId.isBlank())
      throw new FileFlowException(Kind.INVALID_ARGUMENT, "operation_id es obligatorio");
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try {
        String status;
        try (PreparedStatement s = c.prepareStatement(
            "SELECT status::text, operation_id FROM dfs_file WHERE file_id=? AND owner_id=? FOR UPDATE")) {
          s.setObject(1, fileId); s.setObject(2, ownerId);
          try (ResultSet r = s.executeQuery()) {
            if (!r.next()) throw new FileFlowException(Kind.NOT_FOUND, "archivo no encontrado");
            status = r.getString(1);
            if (!operationId.equals(r.getString(2))) throw new FileFlowException(
                Kind.FAILED_PRECONDITION, "operation_id no corresponde al archivo");
          }
        }
        if ("COMMITTED".equals(status)) { c.commit(); return new CommitResult(true, List.of()); }
        List<UUID> missing = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement("""
            SELECT b.block_id FROM dfs_block b
            LEFT JOIN block_location l ON l.block_id=b.block_id AND l.state='WRITTEN'
            WHERE b.file_id=? GROUP BY b.block_id HAVING count(l.block_id)=0 ORDER BY min(b.block_index)
            """)) {
          s.setObject(1, fileId); try (ResultSet r=s.executeQuery()) { while(r.next()) missing.add(r.getObject(1,UUID.class)); }
        }
        if (missing.isEmpty()) try (PreparedStatement s=c.prepareStatement(
            "UPDATE dfs_file SET status='COMMITTED', committed_at=now() WHERE file_id=?")) {
          s.setObject(1,fileId); s.executeUpdate();
        }
        c.commit(); return new CommitResult(missing.isEmpty(), missing);
      } catch (FileFlowException e) { rollback(c); throw e; }
      catch (SQLException e) { rollback(c); throw databaseFailure(e); }
    } catch (SQLException e) { throw databaseFailure(e); }
  }

  @Override public FilePlan manifest(UUID ownerId, String fileId, String path) throws FileFlowException {
    try (Connection c=dataSource.getConnection()) {
      UUID id = parseOptionalUuid(fileId);
      String sql = id != null
          ? "SELECT file_id,path,size_bytes,block_size_bytes,operation_id,status::text FROM dfs_file WHERE owner_id=? AND file_id=? AND status='COMMITTED'"
          : "SELECT file_id,path,size_bytes,block_size_bytes,operation_id,status::text FROM dfs_file WHERE owner_id=? AND path=? AND status='COMMITTED'";
      try (PreparedStatement s=c.prepareStatement(sql)) {
        s.setObject(1,ownerId); if(id != null) s.setObject(2,id); else s.setString(2,path);
        try(ResultSet r=s.executeQuery()) {
          if(!r.next()) throw new FileFlowException(Kind.NOT_FOUND,"archivo confirmado no encontrado");
          return mapFile(c,r);
        }
      }
    } catch(SQLException e){throw databaseFailure(e);}
  }

  @Override public void reportStored(UUID nodeId, String tokenHash, UUID blockId,
                                     long bytes, String checksum) throws FileFlowException {
    if (bytes < 0 || checksum == null || !SHA256.matcher(checksum).matches())
      throw new FileFlowException(Kind.INVALID_ARGUMENT,"tamaño o SHA-256 inválido");
    try(Connection c=dataSource.getConnection()) {
      c.setAutoCommit(false);
      try {
        try(PreparedStatement s=c.prepareStatement(
            "SELECT 1 FROM datanode WHERE datanode_id=? AND node_token_hash=? FOR UPDATE")) {
          s.setObject(1,nodeId);s.setString(2,tokenHash);try(ResultSet r=s.executeQuery()){
            if(!r.next())throw new FileFlowException(Kind.UNAUTHENTICATED,"credenciales del DataNode inválidas");
          }
        }
        long expected; long reserved; String state; String storedChecksum;
        try(PreparedStatement s=c.prepareStatement("""
            SELECT b.size_bytes,l.reserved_bytes,l.state::text,b.checksum_sha256 FROM dfs_block b
            JOIN block_location l ON l.block_id=b.block_id
            WHERE b.block_id=? AND l.datanode_id=? FOR UPDATE
            """)) {
          s.setObject(1,blockId);s.setObject(2,nodeId);try(ResultSet r=s.executeQuery()){
            if(!r.next())throw new FileFlowException(Kind.NOT_FOUND,"bloque no asignado al DataNode");
            expected=r.getLong(1);reserved=r.getLong(2);state=r.getString(3);storedChecksum=r.getString(4);
          }
        }
        if(bytes != expected)throw new FileFlowException(Kind.FAILED_PRECONDITION,"el tamaño escrito no coincide con el plan");
        if("WRITTEN".equals(state) && !checksum.equals(storedChecksum))
          throw new FileFlowException(Kind.FAILED_PRECONDITION,"el bloque ya fue reportado con otro checksum");
        if(!"WRITTEN".equals(state)) {
          try(PreparedStatement s=c.prepareStatement("UPDATE dfs_block SET checksum_sha256=? WHERE block_id=?")){
            s.setString(1,checksum);s.setObject(2,blockId);s.executeUpdate();
          }
          try(PreparedStatement s=c.prepareStatement("UPDATE block_location SET state='WRITTEN',reserved_bytes=0,written_at=now() WHERE block_id=? AND datanode_id=?")){
            s.setObject(1,blockId);s.setObject(2,nodeId);s.executeUpdate();
          }
          try(PreparedStatement s=c.prepareStatement("UPDATE datanode SET reserved_bytes=GREATEST(0,reserved_bytes-?) WHERE datanode_id=?")){
            s.setLong(1,reserved);s.setObject(2,nodeId);s.executeUpdate();
          }
        }
        c.commit();
      } catch(FileFlowException e){rollback(c);throw e;}
      catch(SQLException e){rollback(c);throw databaseFailure(e);}
    } catch(SQLException e){throw databaseFailure(e);}
  }

  private List<BlockPlacement> allocateBlocks(Connection c, UUID fileId, long size, int blockSize,
                                                List<Candidate> nodes) throws SQLException, FileFlowException {
    long count = size == 0 ? 0 : Math.floorDiv(size - 1, blockSize) + 1;
    if (count > Integer.MAX_VALUE) throw new FileFlowException(Kind.INVALID_ARGUMENT,"demasiados bloques");
    List<BlockPlacement> result=new ArrayList<>(); Map<UUID,Integer> assigned=new HashMap<>();
    for(int index=0; index<(int)count; index++) {
      long offset=(long)index*blockSize; long bytes=Math.min(blockSize,size-offset);
      Candidate selected=policy.select(nodes,assigned,bytes,reservedFloor,maxTransfers,targetBlocks)
          .orElseThrow(() -> new FileFlowException(Kind.RESOURCE_EXHAUSTED,"no hay DataNodes vivos con espacio suficiente"));
      try(PreparedStatement s=c.prepareStatement("UPDATE datanode SET reserved_bytes=reserved_bytes+? WHERE datanode_id=? AND status='ALIVE' AND available_bytes>=?")){
        s.setLong(1,bytes);s.setObject(2,selected.id());s.setLong(3,bytes+reservedFloor);
        if(s.executeUpdate()!=1)throw new FileFlowException(Kind.RESOURCE_EXHAUSTED,"cambió la capacidad disponible; reintente");
      }
      UUID blockId;
      try(PreparedStatement s=c.prepareStatement("INSERT INTO dfs_block(file_id,block_index,offset_bytes,size_bytes) VALUES(?,?,?,?) RETURNING block_id")){
        s.setObject(1,fileId);s.setInt(2,index);s.setLong(3,offset);s.setLong(4,bytes);try(ResultSet r=s.executeQuery()){r.next();blockId=r.getObject(1,UUID.class);}
      }
      try(PreparedStatement s=c.prepareStatement("INSERT INTO block_location(block_id,datanode_id,role,state,reserved_bytes) VALUES(?,?,'PRIMARY','PLANNED',?)")){
        s.setObject(1,blockId);s.setObject(2,selected.id());s.setLong(3,bytes);s.executeUpdate();
      }
      result.add(new BlockPlacement(blockId,index,offset,bytes,selected.id(),selected.endpoint(),null));
      assigned.merge(selected.id(),1,Integer::sum);
      int pos=nodes.indexOf(selected); nodes.set(pos,new Candidate(selected.id(),selected.endpoint(),selected.capacityBytes(),
          selected.freeBytes(),selected.reservedBytes()+bytes,selected.activeTransfers(),selected.storedBlocks()));
    }
    return result;
  }

  private List<Candidate> lockEligibleNodes(Connection c) throws SQLException {
    List<Candidate> nodes=new ArrayList<>();
    try(PreparedStatement s=c.prepareStatement("""
        SELECT datanode_id,endpoint,capacity_bytes,free_bytes,reserved_bytes,active_transfers,stored_blocks
        FROM datanode WHERE status='ALIVE' ORDER BY datanode_id FOR UPDATE
        """);ResultSet r=s.executeQuery()){
      while(r.next())nodes.add(new Candidate(r.getObject(1,UUID.class),r.getString(2),r.getLong(3),r.getLong(4),r.getLong(5),r.getInt(6),r.getLong(7)));
    } return nodes;
  }

  private static UUID insertPendingFile(Connection c,UUID owner,String path,long size,int blockSize,String op,long ttl) throws SQLException {
    try(PreparedStatement s=c.prepareStatement("INSERT INTO dfs_file(owner_id,path,size_bytes,block_size_bytes,operation_id,pending_expires_at) VALUES(?,?,?,?,?,?) RETURNING file_id")){
      s.setObject(1,owner);s.setString(2,path);s.setLong(3,size);s.setInt(4,blockSize);s.setString(5,op);
      s.setTimestamp(6,Timestamp.from(Instant.now().plusSeconds(ttl)));try(ResultSet r=s.executeQuery()){r.next();return r.getObject(1,UUID.class);}
    }
  }

  private static void requireParent(Connection c,UUID owner,String parent) throws SQLException,FileFlowException {
    try(PreparedStatement s=c.prepareStatement("SELECT 1 FROM dfs_directory WHERE owner_id=? AND path=? AND deleted_at IS NULL")){
      s.setObject(1,owner);s.setString(2,parent);try(ResultSet r=s.executeQuery()){if(!r.next())throw new FileFlowException(Kind.FAILED_PRECONDITION,"el directorio padre no existe");}
    }
  }

  private FilePlan findByOperation(Connection c,UUID owner,String op) throws SQLException {
    try(PreparedStatement s=c.prepareStatement("SELECT file_id,path,size_bytes,block_size_bytes,operation_id,status::text FROM dfs_file WHERE owner_id=? AND operation_id=?")){
      s.setObject(1,owner);s.setString(2,op);try(ResultSet r=s.executeQuery()){return r.next()?mapFile(c,r):null;}
    }
  }

  private FilePlan mapFile(Connection c,ResultSet file) throws SQLException {
    UUID id=file.getObject("file_id",UUID.class);List<BlockPlacement> blocks=new ArrayList<>();
    try(PreparedStatement s=c.prepareStatement("""
        SELECT b.block_id,b.block_index,b.offset_bytes,b.size_bytes,l.datanode_id,d.endpoint,b.checksum_sha256
        FROM dfs_block b JOIN block_location l ON l.block_id=b.block_id JOIN datanode d ON d.datanode_id=l.datanode_id
        WHERE b.file_id=? AND l.role='PRIMARY' ORDER BY b.block_index
        """)){s.setObject(1,id);try(ResultSet r=s.executeQuery()){while(r.next())blocks.add(new BlockPlacement(r.getObject(1,UUID.class),r.getInt(2),r.getLong(3),r.getLong(4),r.getObject(5,UUID.class),r.getString(6),r.getString(7)));}}
    return new FilePlan(id,file.getString("path"),file.getLong("size_bytes"),file.getInt("block_size_bytes"),file.getString("operation_id"),file.getString("status"),blocks);
  }

  private static void validateAllocation(String path,long size,String op,int blockSize) throws FileFlowException {
    if(path==null||!path.matches("^(/[^/]+)+$")||path.matches(".*(^|/)\\.\\.?(/|$).*")||size<0||op==null||op.isBlank()||op.length()>80||blockSize<=0)
      throw new FileFlowException(Kind.INVALID_ARGUMENT,"path, tamaño, operation_id o block_size inválido");
  }
  private static String parentOf(String path){int i=path.lastIndexOf('/');return i==0?"/":path.substring(0,i);}
  private static UUID parseOptionalUuid(String value)throws FileFlowException{
    if(value==null||value.isBlank())return null;try{return UUID.fromString(value);}catch(IllegalArgumentException e){throw new FileFlowException(Kind.INVALID_ARGUMENT,"file_id inválido");}}
  private static void rollback(Connection c){try{c.rollback();}catch(SQLException ignored){}}
  private static FileFlowException databaseFailure(SQLException e){return new FileFlowException(Kind.INTERNAL,"falló la operación en PostgreSQL",e);}
}
