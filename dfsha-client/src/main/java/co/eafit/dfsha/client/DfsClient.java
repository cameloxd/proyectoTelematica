package co.eafit.dfsha.client;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import co.eafit.dfsha.common.Env;
import co.eafit.dfsha.common.Hash;
import co.eafit.dfsha.common.Rpc;
import co.eafit.dfsha.grpc.v1.AllocateFileRequest;
import co.eafit.dfsha.grpc.v1.AllocateFileResponse;
import co.eafit.dfsha.grpc.v1.BlockChunk;
import co.eafit.dfsha.grpc.v1.BlockLocation;
import co.eafit.dfsha.grpc.v1.BlockPlan;
import co.eafit.dfsha.grpc.v1.BlockWriteResponse;
import co.eafit.dfsha.grpc.v1.CommitFileRequest;
import co.eafit.dfsha.grpc.v1.CommitFileResponse;
import co.eafit.dfsha.grpc.v1.ControlServiceGrpc;
import co.eafit.dfsha.grpc.v1.DataNodeServiceGrpc;
import co.eafit.dfsha.grpc.v1.DirectoryEntry;
import co.eafit.dfsha.grpc.v1.FileManifest;
import co.eafit.dfsha.grpc.v1.FileRef;
import co.eafit.dfsha.grpc.v1.LockRef;
import co.eafit.dfsha.grpc.v1.LockRequest;
import co.eafit.dfsha.grpc.v1.LockResponse;
import co.eafit.dfsha.grpc.v1.LoginRequest;
import co.eafit.dfsha.grpc.v1.PathRequest;
import co.eafit.dfsha.grpc.v1.ReadBlockRequest;
import co.eafit.dfsha.grpc.v1.WriteBlockHeader;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * La API del cliente: todo lo que se puede hacer con DFSha. La consola (ClientMain) solo lee comandos y
 * llama a estos métodos.
 *
 * <ul>
 *   <li>Con los ControlNodes habla con failover: si uno se cae, usa el siguiente.</li>
 *   <li>Los datos van directo entre el cliente y los DataNodes, varios bloques EN PARALELO; el
 *       ControlNode solo entrega el plan (dónde va cada bloque) y nunca toca los datos.</li>
 * </ul>
 */
public final class DfsClient implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(DfsClient.class);
  private static final int CHUNK_BYTES = 1024 * 1024;     // cada mensaje del stream lleva 1 MiB

  private volatile String sessionToken;
  private final Rpc.Failover controlNodes;
  private final Map<String, ManagedChannel> dataNodes = new ConcurrentHashMap<>();
  private final Set<RemoteFile> handles = ConcurrentHashMap.newKeySet();
  private final ExecutorService pool = Executors.newFixedThreadPool(Env.getInt("CLIENT_PARALLELISM", 4));

  public DfsClient(List<String> controlNodeEndpoints) {
    controlNodes = new Rpc.Failover(controlNodeEndpoints, Rpc.bearerToken(() -> sessionToken));
  }

  /** Llama al ControlNode que esté vivo, con 30 s de plazo. */
  private <T> T control(Function<ControlServiceGrpc.ControlServiceBlockingStub, T> call) {
    return controlNodes.call(ch -> call.apply(ControlServiceGrpc.newBlockingStub(ch).withDeadlineAfter(30, TimeUnit.SECONDS)));
  }

  // =================================================================== usuarios y namespace (RF1)

  public void register(String user, String password) {
    control(s -> s.register(LoginRequest.newBuilder().setUsername(user).setPassword(password).build()));
  }

  public void login(String user, String password) {
    if (!handles.isEmpty()) throw new IllegalStateException("cierra los archivos abiertos antes de cambiar de usuario");
    sessionToken = control(s -> s.login(LoginRequest.newBuilder().setUsername(user).setPassword(password).build()))
        .getAccessToken();
  }

  public List<DirectoryEntry> list(String path) {
    return control(s -> s.listDirectory(PathRequest.newBuilder().setPath(path).build())).getEntriesList();
  }

  public String mkdir(String path) {
    return control(s -> s.createDirectory(PathRequest.newBuilder().setPath(path).build())).getMessage();
  }

  /** rm y rmdir (el ControlNode sabe si la ruta es archivo o directorio). */
  public String delete(String path) {
    return control(s -> s.deletePath(PathRequest.newBuilder().setPath(path).build())).getMessage();
  }

  // =================================================================== subida (RF2: put / send)

  /** AllocateFile -> WriteBlock en paralelo (un stream por bloque) -> CommitFile. */
  public void put(Path local, String remote) throws Exception {
    String operationId = UUID.randomUUID().toString();     // si reintentamos, el ControlNode da el mismo plan
    putVersion(local, remote, operationId, "", "");
  }

  private void putVersion(Path local, String remote, String operationId, String replaces, String lease) throws Exception {
    long size = Files.size(local);
    AllocateFileResponse plan = control(s -> s.allocateFile(AllocateFileRequest.newBuilder()
        .setPath(remote).setFileSizeBytes(size).setOperationId(operationId)
        .setReplacesFileId(replaces).setLockToken(lease).build()));

    List<Callable<Void>> uploads = new ArrayList<>();
    for (BlockPlan block : plan.getBlocksList()) uploads.add(() -> writeBlock(local, block));
    runInParallel(uploads);

    for (int attempt = 1; ; attempt++) {
      CommitFileResponse commit = control(s -> s.commitFile(CommitFileRequest.newBuilder()
          .setFileId(plan.getFile().getFileId()).setOperationId(operationId).build()));
      if (commit.getStatus().getOk()) return;
      if (attempt == 3) throw new IllegalStateException("el ControlNode dice que faltan bloques: " + commit.getMissingBlockIdsList());
      for (BlockPlan block : plan.getBlocksList()) {          // reenviar solo los que faltan
        if (commit.getMissingBlockIdsList().contains(block.getBlockId())) writeBlock(local, block);
      }
    }
  }

  /** Sube un bloque con hasta 3 intentos y verifica que el DataNode calculó el mismo SHA-256. */
  private Void writeBlock(Path local, BlockPlan block) throws Exception {
    byte[] data = readRange(local, block.getOffsetBytes(), (int) block.getSizeBytes());
    String sha256 = Hash.sha256(data);
    for (int attempt = 1; ; attempt++) {
      try {
        BlockWriteResponse response = sendBlock(block, data);
        if (!response.getChecksumSha256().equals(sha256)) throw new IllegalStateException("el DataNode recibió otros datos");
        return null;
      } catch (Exception e) {
        if (attempt == 3) throw e;
        log.warn("Bloque {} falló en {} (intento {}): {}", block.getBlockIndex(), block.getDatanodeEndpoint(),
            attempt, e.getMessage());
      }
    }
  }

  /** WriteBlock es un stream del cliente: un header y luego trozos de 1 MiB. La respuesta llega al final. */
  private BlockWriteResponse sendBlock(BlockPlan block, byte[] data) throws Exception {
    CompletableFuture<BlockWriteResponse> result = new CompletableFuture<>();
    StreamObserver<BlockChunk> stream = DataNodeServiceGrpc.newStub(dataNode(block.getDatanodeEndpoint()))
        .withDeadlineAfter(60, TimeUnit.SECONDS)
        .writeBlock(new StreamObserver<>() {
          @Override public void onNext(BlockWriteResponse response) { result.complete(response); }
          @Override public void onError(Throwable t) { result.completeExceptionally(t); }
          @Override public void onCompleted() { }
        });
    stream.onNext(BlockChunk.newBuilder().setHeader(WriteBlockHeader.newBuilder()
        .setBlockId(block.getBlockId()).setWriteToken(block.getWriteToken()).setExpectedSizeBytes(data.length)).build());
    for (int pos = 0; pos < data.length; pos += CHUNK_BYTES) {
      stream.onNext(BlockChunk.newBuilder().setData(ByteString.copyFrom(data, pos, Math.min(CHUNK_BYTES, data.length - pos))).build());
    }
    stream.onCompleted();
    return unwrap(result);
  }

  // =================================================================== descarga (RF2: get / receive)

  /** GetFileManifest -> ReadBlock en paralelo con failover -> verificar SHA-256 -> escribir cada bloque en su offset. */
  public void get(String remote, Path local) throws Exception {
    download(manifest(remote), local);
  }

  private void download(FileManifest manifest, Path local) throws Exception {
    Path partial = local.resolveSibling(local.getFileName() + ".part");
    try (FileChannel out = FileChannel.open(partial, CREATE, WRITE, TRUNCATE_EXISTING)) {
      List<Callable<Void>> downloads = new ArrayList<>();
      for (BlockPlan block : manifest.getBlocksList()) {
        downloads.add(() -> {
          ByteBuffer data = ByteBuffer.wrap(readWithFailover(block, 0, 0));
          long position = block.getOffsetBytes();
          while (data.hasRemaining()) position += out.write(data, position);   // cada bloque en su lugar
          return null;
        });
      }
      runInParallel(downloads);
    }
    Files.move(partial, local, StandardCopyOption.REPLACE_EXISTING);
  }

  /** Lectura parcial (RF3: read): trae solo los pedazos de bloque que tocan [offset, offset + length). */
  public byte[] read(String remote, long offset, int length) {
    requireRange(offset, length);
    FileManifest manifest = manifest(remote);
    long end = Math.min(offset + length, manifest.getFileSizeBytes());
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    for (BlockPlan block : manifest.getBlocksList()) {
      long blockStart = block.getOffsetBytes();
      long blockEnd = blockStart + block.getSizeBytes();
      if (blockEnd <= offset || blockStart >= end) continue;                // este bloque no toca el rango
      long from = Math.max(offset, blockStart) - blockStart;
      long to = Math.min(end, blockEnd) - blockStart;
      result.writeBytes(readWithFailover(block, from, to - from));
    }
    return result.toByteArray();
  }

  private FileManifest manifest(String remote) {
    return control(s -> s.getFileManifest(FileRef.newBuilder().setPath(remote).build()));
  }

  /**
   * Prueba las copias en el orden del manifiesto (la menos cargada primero). Si un DataNode no responde,
   * da error o el SHA-256 no coincide, pasa a la siguiente copia sin que el usuario lo note.
   */
  private byte[] readWithFailover(BlockPlan block, long offset, long length) {
    Exception last = null;
    for (BlockLocation copy : block.getReplicasList()) {
      try {
        byte[] data = readBlock(copy, block.getBlockId(), offset, length);
        boolean wholeBlock = offset == 0 && length == 0;
        if (wholeBlock && !Hash.sha256(data).equals(block.getChecksumSha256())) {
          throw new IllegalStateException("el SHA-256 no coincide");
        }
        log.info("Bloque {} leído de {}", block.getBlockIndex(), copy.getDatanodeEndpoint());
        return data;
      } catch (Exception e) {
        log.warn("Bloque {}: {} falló ({}); pruebo otra copia", block.getBlockIndex(), copy.getDatanodeEndpoint(),
            e.getMessage());
        last = e;
      }
    }
    throw new IllegalStateException("ninguna copia del bloque " + block.getBlockIndex() + " está disponible", last);
  }

  private byte[] readBlock(BlockLocation copy, String blockId, long offset, long length) {
    ByteArrayOutputStream data = new ByteArrayOutputStream();
    DataNodeServiceGrpc.newBlockingStub(dataNode(copy.getDatanodeEndpoint())).withDeadlineAfter(15, TimeUnit.SECONDS)
        .readBlock(ReadBlockRequest.newBuilder().setBlockId(blockId).setReadToken(copy.getReadToken())
            .setOffsetBytes(offset).setLengthBytes(length).build())
        .forEachRemaining(chunk -> data.writeBytes(chunk.getData().toByteArray()));
    return data.toByteArray();
  }

  // =================================================================== lock (RF3)

  public LockResponse lock(String path, int ttlSeconds) {
    return control(s -> s.acquireLock(LockRequest.newBuilder().setPath(path).setTtlSeconds(ttlSeconds).build()));
  }

  public void unlock(String lockId) {
    control(s -> s.releaseLock(LockRef.newBuilder().setLockId(lockId).build()));
  }

  /** RF3: instantanea local verificable; rw publica una version copy-on-write SOLO en close(). */
  public RemoteFile open(String path, String mode) throws Exception {
    if (!mode.equals("r") && !mode.equals("rw")) throw new IllegalArgumentException("modo: r o rw");
    String lease = "";
    Path cache = null;
    try {
      if (mode.equals("rw")) lease = lock(path, 600).getLockId();
      FileManifest snapshot = manifest(path);
      cache = Files.createTempFile("dfsha-open-", ".cache");
      download(snapshot, cache);
      RemoteFile handle = new RemoteFile(path, snapshot.getFile().getFileId(), lease, cache, mode.equals("rw"));
      handles.add(handle);
      return handle;
    } catch (Exception e) {
      if (cache != null) {
        try { Files.deleteIfExists(cache); Files.deleteIfExists(cache.resolveSibling(cache.getFileName() + ".part")); }
        catch (Exception cleanup) { e.addSuppressed(cleanup); }
      }
      if (!lease.isEmpty()) try { unlock(lease); } catch (Exception cleanup) { e.addSuppressed(cleanup); }
      throw e;
    }
  }

  private static void requireRange(long offset, int length) {
    if (offset < 0 || length < 0 || length > 64 * 1024 * 1024 || offset > Long.MAX_VALUE - length) {
      throw new IllegalArgumentException("rango invalido (maximo 64 MiB por llamada)");
    }
  }

  public final class RemoteFile implements AutoCloseable {
    private final String path, version, lease;
    private final String operation = UUID.randomUUID().toString();
    private final Path cache;
    private final FileChannel file;
    private final boolean writable;
    private boolean dirty, closed;

    private RemoteFile(String path, String version, String lease, Path cache, boolean writable) throws IOException {
      this.path = path; this.version = version; this.lease = lease; this.cache = cache; this.writable = writable;
      this.file = FileChannel.open(cache, READ, WRITE);
    }

    private void checkOpen() { if (closed) throw new IllegalStateException("archivo cerrado"); }

    public synchronized long size() throws IOException { checkOpen(); return file.size(); }

    public synchronized byte[] read(long offset, int length) throws IOException {
      checkOpen(); requireRange(offset, length);
      int available = (int) Math.min(length, Math.max(0, file.size() - offset));
      ByteBuffer data = ByteBuffer.allocate(available);
      while (data.hasRemaining()) { if (file.read(data, offset + data.position()) < 0) break; }
      return Arrays.copyOf(data.array(), data.position());
    }

    public synchronized void write(long offset, byte[] bytes) throws IOException {
      checkOpen();
      if (!writable) throw new IllegalStateException("archivo abierto solo para lectura");
      requireRange(offset, bytes.length);
      ByteBuffer data = ByteBuffer.wrap(bytes);
      while (data.hasRemaining()) offset += file.write(data, offset);
      if (bytes.length > 0) dirty = true;
    }

    @Override public synchronized void close() throws Exception {
      if (closed) return;
      if (dirty) {
        file.force(true);
        // Lease y version se vuelven a verificar en AllocateFile y CommitFile, no solo al abrir.
        putVersion(cache, path, operation, version, lease);
        dirty = false;
      }
      dispose();
    }

    /** Descarta cambios no confirmados; no publica una version. */
    public synchronized void abort() throws Exception { if (!closed) dispose(); }

    private void dispose() throws Exception {
      closed = true;
      handles.remove(this);
      try { file.close(); }
      finally {
        try { Files.deleteIfExists(cache); }
        finally { if (!lease.isEmpty()) unlock(lease); }
      }
    }
  }

  // =================================================================== ayudas

  /** Un canal por DataNode, reutilizado entre bloques. */
  private ManagedChannel dataNode(String endpoint) {
    return dataNodes.computeIfAbsent(endpoint, e -> Rpc.channel(e));
  }

  private void runInParallel(List<Callable<Void>> tasks) throws Exception {
    List<Future<Void>> running = new ArrayList<>();
    for (Callable<Void> task : tasks) running.add(pool.submit(task));
    for (Future<Void> task : running) unwrap(task);
  }

  private static <T> T unwrap(Future<T> future) throws Exception {
    try {
      return future.get();
    } catch (ExecutionException e) {
      throw e.getCause() instanceof Exception cause ? cause : e;
    }
  }

  private static byte[] readRange(Path file, long offset, int size) throws IOException {
    try (FileChannel in = FileChannel.open(file, READ)) {
      ByteBuffer buffer = ByteBuffer.allocate(size);
      while (buffer.hasRemaining()) {
        if (in.read(buffer, offset + buffer.position()) < 0) throw new EOFException("el archivo cambió mientras se subía");
      }
      return buffer.array();
    }
  }

  @Override
  public void close() {
    for (RemoteFile handle : List.copyOf(handles)) {
      try { handle.abort(); } catch (Exception e) { log.warn("No pude liberar un archivo abierto: {}", e.getClass().getSimpleName()); }
    }
    pool.shutdownNow();
    controlNodes.close();
    dataNodes.values().forEach(ManagedChannel::shutdownNow);
  }
}
