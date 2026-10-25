package co.eafit.dfsha.control;

import static io.grpc.Status.ALREADY_EXISTS;
import static io.grpc.Status.FAILED_PRECONDITION;
import static io.grpc.Status.INVALID_ARGUMENT;
import static io.grpc.Status.NOT_FOUND;
import static io.grpc.Status.RESOURCE_EXHAUSTED;
import static io.grpc.Status.UNAUTHENTICATED;

import co.eafit.dfsha.common.DfsPaths;
import co.eafit.dfsha.common.Env;
import co.eafit.dfsha.common.Rabbit;
import co.eafit.dfsha.common.Rpc;
import co.eafit.dfsha.common.Tokens;
import co.eafit.dfsha.grpc.v1.AllocateFileRequest;
import co.eafit.dfsha.grpc.v1.AllocateFileResponse;
import co.eafit.dfsha.grpc.v1.BlockDeleteTask;
import co.eafit.dfsha.grpc.v1.BlockLocation;
import co.eafit.dfsha.grpc.v1.BlockPlan;
import co.eafit.dfsha.grpc.v1.CommitFileRequest;
import co.eafit.dfsha.grpc.v1.CommitFileResponse;
import co.eafit.dfsha.grpc.v1.ControlServiceGrpc;
import co.eafit.dfsha.grpc.v1.DirectoryEntry;
import co.eafit.dfsha.grpc.v1.FileManifest;
import co.eafit.dfsha.grpc.v1.FileRef;
import co.eafit.dfsha.grpc.v1.ListResponse;
import co.eafit.dfsha.grpc.v1.LockRef;
import co.eafit.dfsha.grpc.v1.LockRequest;
import co.eafit.dfsha.grpc.v1.LockResponse;
import co.eafit.dfsha.grpc.v1.LoginRequest;
import co.eafit.dfsha.grpc.v1.LoginResponse;
import co.eafit.dfsha.grpc.v1.PathRequest;
import co.eafit.dfsha.grpc.v1.Status;
import io.grpc.stub.StreamObserver;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lo que el cliente le pide al ControlNode (servicio ControlService del .proto).
 *
 * <p>Cada rpc es un método que valida, hace sus consultas en UNA transacción y responde. Quien llama
 * sale del token de sesión ({@link #caller()}) y cada usuario solo ve sus propios archivos.
 */
public final class ControlServiceImpl extends ControlServiceGrpc.ControlServiceImplBase {
  private static final Logger log = LoggerFactory.getLogger(ControlServiceImpl.class);

  private final Db db;
  private final Tokens tokens;
  private final Rabbit rabbit;
  private final Placement placement = Placement.fromEnv();
  private final int blockSize = Env.getInt("BLOCK_SIZE_BYTES", 8 * 1024 * 1024);
  private final long sessionTtl = Env.getLong("SESSION_TTL_SECONDS", 3600);
  private final long blockTokenTtl = Env.getLong("BLOCK_TOKEN_TTL_SECONDS", 900);

  public ControlServiceImpl(Db db, Tokens tokens, Rabbit rabbit) {
    this.db = db;
    this.tokens = tokens;
    this.rabbit = rabbit;
  }

  // =================================================================== usuarios

  @Override
  public void register(LoginRequest req, StreamObserver<Status> out) {
    Rpc.respond(out, () -> {
      if (!req.getUsername().matches("[A-Za-z0-9_.-]{3,40}") || req.getPassword().length() < 4) {
        throw Rpc.error(INVALID_ARGUMENT, "usuario de 3 a 40 caracteres y contraseña de mínimo 4");
      }
      db.tx(c -> Db.update(c, "INSERT INTO app_user(username, password_hash) VALUES (?, ?)",
          req.getUsername(), Passwords.hash(req.getPassword())));
      log.info("Usuario {} creado", req.getUsername());
      return Rpc.ok("usuario creado");
    });
  }

  @Override
  public void login(LoginRequest req, StreamObserver<LoginResponse> out) {
    Rpc.respond(out, () -> {
      User user = db.tx(c -> Db.one(c, "SELECT user_id, password_hash FROM app_user WHERE username = ?",
          r -> new User(r.getString(1), r.getString(2)), req.getUsername()));
      if (user == null || !Passwords.matches(req.getPassword(), user.passwordHash())) {
        throw Rpc.error(UNAUTHENTICATED, "usuario o contraseña incorrectos");
      }
      return LoginResponse.newBuilder()
          .setAccessToken(tokens.issue(Tokens.SESSION, user.id(), sessionTtl))
          .setExpiresAtEpochSeconds(Tokens.expiresAt(sessionTtl))
          .setUserId(user.id())
          .build();
    });
  }

  // =================================================================== namespace (RF1)

  @Override
  public void createDirectory(PathRequest req, StreamObserver<Status> out) {
    Rpc.respond(out, () -> {
      UUID owner = caller();
      String path = DfsPaths.resolve("/", req.getPath());
      db.tx(c -> {
        requireDirectory(c, owner, DfsPaths.parent(path));
        if (committedFile(c, owner, path) != null) throw Rpc.error(ALREADY_EXISTS, "ya hay un archivo con ese nombre");
        return Db.update(c, "INSERT INTO dfs_directory(owner_id, path) VALUES (?, ?)", owner, path);
      });
      return Rpc.ok("directorio creado: " + path);
    });
  }

  @Override
  public void listDirectory(PathRequest req, StreamObserver<ListResponse> out) {
    Rpc.respond(out, () -> {
      UUID owner = caller();
      String path = DfsPaths.resolve("/", req.getPath());
      return db.tx(c -> {
        requireDirectory(c, owner, path);
        List<DirectoryEntry> dirs = Db.query(c, """
            SELECT path FROM dfs_directory
            WHERE owner_id = ? AND parent_path = ? AND deleted_at IS NULL ORDER BY path""",
            r -> DirectoryEntry.newBuilder().setPath(r.getString(1)).setDirectory(true).build(), owner, path);
        List<DirectoryEntry> files = Db.query(c, """
            SELECT path, size_bytes FROM dfs_file
            WHERE owner_id = ? AND parent_path = ? AND status = 'COMMITTED' ORDER BY path""",
            r -> DirectoryEntry.newBuilder().setPath(r.getString(1)).setSizeBytes(r.getLong(2)).build(), owner, path);
        return ListResponse.newBuilder().addAllEntries(dirs).addAllEntries(files).build();
      });
    });
  }

  /** rm y rmdir. Un archivo se marca DELETED y sus bloques se borran después, por RabbitMQ. */
  @Override
  public void deletePath(PathRequest req, StreamObserver<Status> out) {
    Rpc.respond(out, () -> {
      UUID owner = caller();
      String path = DfsPaths.resolve("/", req.getPath());
      if (path.equals("/")) throw Rpc.error(INVALID_ARGUMENT, "no se puede borrar /");
      List<Copy> copies = db.tx(c -> {
        UUID fileId = Db.one(c, "SELECT file_id FROM dfs_file WHERE owner_id = ? AND path = ? AND status <> 'DELETED'",
            r -> r.getObject(1, UUID.class), owner, path);
        if (fileId == null) {
          deleteEmptyDirectory(c, owner, path);
          return null;
        }
        if (Db.one(c, "SELECT 1 FROM file_lock WHERE file_id = ? AND expires_at > now()", r -> 1, fileId) != null) {
          throw Rpc.error(FAILED_PRECONDITION, "el archivo tiene lock: haz unlock o espera a que venza");
        }
        Db.update(c, "UPDATE dfs_file SET status = 'DELETED', deleted_at = now() WHERE file_id = ?", fileId);
        return Db.query(c, """
            UPDATE block_location l SET state = 'DELETED'
            FROM dfs_block b
            WHERE b.block_id = l.block_id AND b.file_id = ? AND l.state <> 'DELETED'
            RETURNING l.block_id, l.datanode_id""", r -> new Copy(r.getString(1), r.getString(2), null), fileId);
      });
      if (copies == null) return Rpc.ok("directorio borrado: " + path);
      for (Copy copy : copies) {     // una tarea por copia: cada DataNode borra lo suyo
        rabbit.declareNodeQueues(copy.nodeId());
        rabbit.publish(Rabbit.deleteKey(copy.nodeId()),
            BlockDeleteTask.newBuilder().setBlockId(copy.blockId()).build().toByteArray(), true);
      }
      log.info("Borrado {}: {} copias de bloques enviadas a borrar", path, copies.size());
      return Rpc.ok("archivo borrado: " + path);
    });
  }

  private static void deleteEmptyDirectory(Connection c, UUID owner, String path) throws SQLException {
    if (Db.one(c, "SELECT 1 FROM dfs_directory WHERE owner_id = ? AND path = ? AND deleted_at IS NULL",
        r -> 1, owner, path) == null) {
      throw Rpc.error(NOT_FOUND, "no existe " + path);
    }
    Integer child = Db.one(c, """
        SELECT 1 FROM dfs_directory WHERE owner_id = ? AND parent_path = ? AND deleted_at IS NULL
        UNION ALL
        SELECT 1 FROM dfs_file WHERE owner_id = ? AND parent_path = ? AND status <> 'DELETED'
        LIMIT 1""", r -> 1, owner, path, owner, path);
    if (child != null) throw Rpc.error(FAILED_PRECONDITION, "el directorio no está vacío");
    Db.update(c, "UPDATE dfs_directory SET deleted_at = now() WHERE owner_id = ? AND path = ? AND deleted_at IS NULL",
        owner, path);
  }

  // =================================================================== subida (RF2: put)

  /** Paso 1 de la subida: crea el archivo PENDING, lo parte en bloques y elige un DataNode por bloque. */
  @Override
  public void allocateFile(AllocateFileRequest req, StreamObserver<AllocateFileResponse> out) {
    Rpc.respond(out, () -> {
      UUID owner = caller();
      String path = DfsPaths.resolve("/", req.getPath());
      long size = req.getFileSizeBytes();
      String operationId = req.getOperationId();
      if (path.equals("/") || size < 0 || operationId.isBlank() || operationId.length() > 80) {
        throw Rpc.error(INVALID_ARGUMENT, "path, tamaño u operation_id inválido");
      }
      return db.tx(c -> {
        // 1. Idempotencia: si el cliente reintenta con el mismo operation_id, recibe el mismo plan.
        FileRow previous = Db.one(c, "SELECT file_id, path, size_bytes FROM dfs_file WHERE owner_id = ? AND operation_id = ?",
            FileRow::read, owner, operationId);
        if (previous != null) {
          if (!previous.path().equals(path) || previous.size() != size) {
            throw Rpc.error(FAILED_PRECONDITION, "ese operation_id ya se usó con otro archivo");
          }
          return uploadPlan(c, previous.id(), path);
        }

        // 2. El directorio padre debe existir y el nombre debe estar libre.
        requireDirectory(c, owner, DfsPaths.parent(path));
        if (Db.one(c, "SELECT 1 FROM dfs_directory WHERE owner_id = ? AND path = ? AND deleted_at IS NULL",
            r -> 1, owner, path) != null) {
          throw Rpc.error(ALREADY_EXISTS, path + " es un directorio");
        }
        if (committedFile(c, owner, path) != null) {
          throw Rpc.error(ALREADY_EXISTS, path + " ya existe (los archivos no se sobrescriben: haz rm primero)");
        }
        // Una subida anterior al mismo path que quedó a medias (PENDING) se descarta y libera su espacio.
        Db.update(c, """
            UPDATE block_location l SET state = 'DELETED'
            FROM dfs_block b, dfs_file f
            WHERE l.block_id = b.block_id AND b.file_id = f.file_id
              AND f.owner_id = ? AND f.path = ? AND f.status = 'PENDING'""", owner, path);
        Db.update(c, "UPDATE dfs_file SET status = 'DELETED', deleted_at = now() WHERE owner_id = ? AND path = ? AND status = 'PENDING'",
            owner, path);

        // 3. Un AllocateFile a la vez elige nodos, para que dos subidas no cuenten el mismo espacio libre.
        Db.one(c, "SELECT pg_advisory_xact_lock(1)", r -> 1);
        UUID fileId = Db.one(c, """
            INSERT INTO dfs_file(owner_id, path, size_bytes, block_size_bytes, operation_id)
            VALUES (?, ?, ?, ?, ?) RETURNING file_id""",
            r -> r.getObject(1, UUID.class), owner, path, size, blockSize, operationId);

        // 4. Bloques de BLOCK_SIZE_BYTES (el último puede ser más pequeño): 20 MiB = 8 + 8 + 4 MiB.
        List<Placement.Node> nodes = Placement.aliveNodes(c);
        Map<UUID, Integer> blocksOfThisFile = new HashMap<>();
        int count = 0;
        for (long offset = 0; offset < size; offset += blockSize) {
          long bytes = Math.min(blockSize, size - offset);
          Placement.Node node = placement.choosePrimary(nodes, blocksOfThisFile, bytes);
          if (node == null) throw Rpc.error(RESOURCE_EXHAUSTED, "no hay DataNodes vivos con espacio suficiente");
          UUID blockId = Db.one(c, """
              INSERT INTO dfs_block(file_id, block_index, offset_bytes, size_bytes)
              VALUES (?, ?, ?, ?) RETURNING block_id""", r -> r.getObject(1, UUID.class), fileId, count, offset, bytes);
          Db.update(c, "INSERT INTO block_location(block_id, datanode_id, role) VALUES (?, ?, 'PRIMARY')", blockId, node.id);
          node.assign(bytes);
          blocksOfThisFile.merge(node.id, 1, Integer::sum);
          count++;
        }
        log.info("AllocateFile {}: {} bytes en {} bloques sobre {} nodos", path, size, count, blocksOfThisFile.size());
        return uploadPlan(c, fileId, path);
      });
    });
  }

  /** El plan de subida: por cada bloque, su DataNode primario y un token de escritura firmado. */
  private AllocateFileResponse uploadPlan(Connection c, UUID fileId, String path) throws SQLException {
    List<BlockPlan> blocks = Db.query(c, """
        SELECT b.block_id, b.block_index, b.offset_bytes, b.size_bytes, d.datanode_id, d.endpoint
        FROM dfs_block b
        JOIN block_location l ON l.block_id = b.block_id AND l.role = 'PRIMARY'
        JOIN datanode d ON d.datanode_id = l.datanode_id
        WHERE b.file_id = ? ORDER BY b.block_index""",
        r -> BlockPlan.newBuilder()
            .setBlockId(r.getString(1)).setBlockIndex(r.getInt(2))
            .setOffsetBytes(r.getLong(3)).setSizeBytes(r.getLong(4))
            .setDatanodeId(r.getString(5)).setDatanodeEndpoint(r.getString(6))
            .setWriteToken(tokens.issue(Tokens.WRITE, r.getString(1), blockTokenTtl))
            .setTokenExpiresAtEpochSeconds(Tokens.expiresAt(blockTokenTtl))
            .build(),
        fileId);
    return AllocateFileResponse.newBuilder()
        .setFile(FileRef.newBuilder().setFileId(fileId.toString()).setPath(path))
        .setBlockSizeBytes(blockSize)
        .addAllBlocks(blocks)
        .build();
  }

  /** Paso 3 de la subida: el archivo pasa a COMMITTED (visible) si cada bloque tiene al menos una copia. */
  @Override
  public void commitFile(CommitFileRequest req, StreamObserver<CommitFileResponse> out) {
    Rpc.respond(out, () -> {
      UUID owner = caller();
      UUID fileId = uuid(req.getFileId());
      return db.tx(c -> {
        String status = Db.one(c, "SELECT status::text FROM dfs_file WHERE file_id = ? AND owner_id = ? FOR UPDATE",
            r -> r.getString(1), fileId, owner);
        if (status == null || status.equals("DELETED")) throw Rpc.error(NOT_FOUND, "archivo no encontrado");
        // Un bloque está listo cuando un DataNode confirmó tenerlo (ReportBlockStored -> WRITTEN).
        List<String> missing = Db.query(c, """
            SELECT b.block_id FROM dfs_block b
            WHERE b.file_id = ? AND NOT EXISTS (
              SELECT 1 FROM block_location l WHERE l.block_id = b.block_id AND l.state = 'WRITTEN')
            ORDER BY b.block_index""", r -> r.getString(1), fileId);
        if (!missing.isEmpty()) {
          return CommitFileResponse.newBuilder()
              .setStatus(Status.newBuilder().setOk(false).setCode("MISSING_BLOCKS").setMessage("faltan bloques"))
              .addAllMissingBlockIds(missing).build();
        }
        Db.update(c, "UPDATE dfs_file SET status = 'COMMITTED', committed_at = now() WHERE file_id = ? AND status = 'PENDING'",
            fileId);
        return CommitFileResponse.newBuilder().setStatus(Rpc.ok("archivo confirmado")).build();
      });
    });
  }

  // =================================================================== descarga (RF2: get)

  /** Dónde está cada bloque: todas las copias escritas en nodos vivos, la menos cargada primero. */
  @Override
  public void getFileManifest(FileRef req, StreamObserver<FileManifest> out) {
    Rpc.respond(out, () -> {
      UUID owner = caller();
      String path = DfsPaths.resolve("/", req.getPath());
      return db.tx(c -> {
        FileRow file = Db.one(c, "SELECT file_id, path, size_bytes FROM dfs_file WHERE owner_id = ? AND path = ? AND status = 'COMMITTED'",
            FileRow::read, owner, path);
        if (file == null) throw Rpc.error(NOT_FOUND, "no existe el archivo " + path);

        // Recién subido, solo la primaria está WRITTEN: se lee de ahí hasta que exista otra réplica.
        List<Copy> copies = Db.query(c, """
            SELECT l.block_id, d.datanode_id, d.endpoint
            FROM block_location l
            JOIN dfs_block b ON b.block_id = l.block_id
            JOIN datanode d ON d.datanode_id = l.datanode_id
            WHERE b.file_id = ? AND l.state = 'WRITTEN' AND d.status <> 'DEAD'
            ORDER BY d.status = 'ALIVE' DESC, d.active_transfers, l.role""",
            r -> new Copy(r.getString(1), r.getString(2), r.getString(3)), file.id());

        List<BlockPlan.Builder> blocks = Db.query(c, """
            SELECT block_id, block_index, offset_bytes, size_bytes, checksum_sha256
            FROM dfs_block WHERE file_id = ? ORDER BY block_index""",
            r -> BlockPlan.newBuilder().setBlockId(r.getString(1)).setBlockIndex(r.getInt(2))
                .setOffsetBytes(r.getLong(3)).setSizeBytes(r.getLong(4)).setChecksumSha256(String.valueOf(r.getString(5))),
            file.id());

        FileManifest.Builder manifest = FileManifest.newBuilder()
            .setFile(FileRef.newBuilder().setFileId(file.id().toString()).setPath(path))
            .setFileSizeBytes(file.size());
        for (BlockPlan.Builder block : blocks) {
          String readToken = tokens.issue(Tokens.READ, block.getBlockId(), blockTokenTtl);
          block.setReadToken(readToken).setTokenExpiresAtEpochSeconds(Tokens.expiresAt(blockTokenTtl));
          for (Copy copy : copies) {
            if (copy.blockId().equals(block.getBlockId())) {
              block.addReplicas(BlockLocation.newBuilder().setDatanodeId(copy.nodeId()).setDatanodeEndpoint(copy.endpoint())
                  .setReadToken(readToken).setTokenExpiresAtEpochSeconds(block.getTokenExpiresAtEpochSeconds()));
            }
          }
          if (block.getReplicasCount() > 0) {     // compatibilidad con v1: la primera copia en los campos viejos
            block.setDatanodeId(block.getReplicas(0).getDatanodeId()).setDatanodeEndpoint(block.getReplicas(0).getDatanodeEndpoint());
          }
          manifest.addBlocks(block);
        }
        return manifest.build();
      });
    });
  }

  // =================================================================== lock (RF3)

  /** Lock exclusivo con expiración: si el cliente se cae, el lock vence solo. */
  @Override
  public void acquireLock(LockRequest req, StreamObserver<LockResponse> out) {
    Rpc.respond(out, () -> {
      UUID owner = caller();
      String path = DfsPaths.resolve("/", req.getPath());
      int ttl = req.getTtlSeconds() > 0 ? req.getTtlSeconds() : 60;
      return db.tx(c -> {
        UUID fileId = committedFile(c, owner, path);
        if (fileId == null) throw Rpc.error(NOT_FOUND, "no existe el archivo " + path);
        // Si hay un lock vigente, el WHERE del UPDATE no se cumple y no vuelve ninguna fila.
        LockResponse lock = Db.one(c, """
            INSERT INTO file_lock(file_id, owner_id, lock_token, expires_at)
            VALUES (?, ?, gen_random_uuid(), now() + ? * interval '1 second')
            ON CONFLICT (file_id) DO UPDATE
              SET owner_id = EXCLUDED.owner_id, lock_token = EXCLUDED.lock_token,
                  acquired_at = now(), expires_at = EXCLUDED.expires_at
              WHERE file_lock.expires_at < now()
            RETURNING lock_token, extract(epoch FROM expires_at)::bigint""",
            r -> LockResponse.newBuilder().setStatus(Rpc.ok("lock adquirido"))
                .setLockId(r.getString(1)).setExpiresAtEpochSeconds(r.getLong(2)).build(),
            fileId, owner, ttl);
        if (lock == null) throw Rpc.error(FAILED_PRECONDITION, "el archivo ya está bloqueado por otro cliente");
        return lock;
      });
    });
  }

  @Override
  public void releaseLock(LockRef req, StreamObserver<Status> out) {
    Rpc.respond(out, () -> {
      UUID owner = caller();
      UUID lockId = uuid(req.getLockId());
      int deleted = db.tx(c -> Db.update(c, "DELETE FROM file_lock WHERE lock_token = ? AND owner_id = ?", lockId, owner));
      if (deleted == 0) throw Rpc.error(NOT_FOUND, "ese lock no existe");
      return Rpc.ok("lock liberado");
    });
  }

  // =================================================================== ayudas

  /** El user_id de quien llama: lo puso el interceptor de autenticación al validar el token. */
  private static UUID caller() {
    return UUID.fromString(Rpc.USER_ID.get());
  }

  private static UUID uuid(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      throw Rpc.error(INVALID_ARGUMENT, "identificador inválido: " + value);
    }
  }

  private static void requireDirectory(Connection c, UUID owner, String path) throws SQLException {
    if (Db.one(c, "SELECT 1 FROM dfs_directory WHERE owner_id = ? AND path = ? AND deleted_at IS NULL",
        r -> 1, owner, path) == null) {
      throw Rpc.error(NOT_FOUND, "no existe el directorio " + path);
    }
  }

  private static UUID committedFile(Connection c, UUID owner, String path) throws SQLException {
    return Db.one(c, "SELECT file_id FROM dfs_file WHERE owner_id = ? AND path = ? AND status = 'COMMITTED'",
        r -> r.getObject(1, UUID.class), owner, path);
  }

  private record User(String id, String passwordHash) { }

  private record Copy(String blockId, String nodeId, String endpoint) { }

  private record FileRow(UUID id, String path, long size) {
    static FileRow read(java.sql.ResultSet r) throws SQLException {
      return new FileRow(r.getObject(1, UUID.class), r.getString(2), r.getLong(3));
    }
  }
}
