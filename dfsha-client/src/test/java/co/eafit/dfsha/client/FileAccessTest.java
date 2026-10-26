package co.eafit.dfsha.client;

import static org.junit.jupiter.api.Assertions.*;
import static java.nio.charset.StandardCharsets.UTF_8;
import co.eafit.dfsha.common.Hash;
import co.eafit.dfsha.grpc.v1.*;
import com.google.protobuf.ByteString;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.*;

/** Pruebas de la API del cliente sobre gRPC real y un fixture de protocolo acotado.
 * No simula PostgreSQL ni certifica las reglas del servidor: esas se prueban en controlnode. */
@Timeout(20)
class FileAccessTest {
  private final ProtocolFixture fixture = new ProtocolFixture();
  private Server server;
  private DfsClient client;

  @BeforeEach void start() throws Exception {
    server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
        .addService(fixture.control).addService(fixture.data).build().start();
    fixture.endpoint = "127.0.0.1:" + server.getPort();
    client = new DfsClient(List.of(fixture.endpoint));
    client.login("alice", "only-test");
  }
  @AfterEach void stop() throws Exception {
    if (client != null) client.close();
    if (server != null) server.shutdownNow().awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
  }
  @Test void partialWriteIsVisibleOnlyAfterExplicitClose() throws Exception {
    var reader = client.open("/file", "r");
    var writer = client.open("/file", "rw");
    writer.write(6, "DFSha".getBytes(UTF_8));
    assertEquals("hello world", new String(client.read("/file", 0, 100), UTF_8));
    assertEquals("hello DFSha", new String(writer.read(0, 100), UTF_8));
    writer.close(); writer.close();
    assertEquals("hello DFSha", new String(client.read("/file", 0, 100), UTF_8));
    assertEquals("hello world", new String(reader.read(0, 100), UTF_8)); // instantanea independiente
    reader.close();
    assertThrows(IllegalStateException.class, () -> reader.read(0, 1));
  }
  @Test void abortNeverPublishesDirtyCache() throws Exception {
    var writer = client.open("/file", "rw"); writer.write(0, new byte[] {1}); writer.abort();
    assertEquals("hello world", new String(client.read("/file", 0, 100), UTF_8));
    assertEquals(0, fixture.commits);
    assertEquals("", fixture.lease);
  }
  @Test void expiredLeaseCannotReplaceRemoteData() throws Exception {
    var writer = client.open("/file", "rw"); writer.write(0, "bad".getBytes(UTF_8));
    fixture.lease = "expired";
    assertThrows(io.grpc.StatusRuntimeException.class, writer::close);
    assertEquals("hello world", new String(client.read("/file", 0, 100), UTF_8));
    writer.abort();
  }
  @Test void readOnlyAndInvalidRangesAreRejected() throws Exception {
    try (var file = client.open("/file", "r")) {
      assertThrows(IllegalStateException.class, () -> file.write(0, new byte[] {1}));
      assertThrows(IllegalArgumentException.class, () -> file.read(-1, 3));
      assertThrows(IllegalArgumentException.class, () -> file.read(0, -1));
      assertThrows(IllegalArgumentException.class, () -> file.read(Long.MAX_VALUE, 2));
      assertArrayEquals(new byte[0], file.read(99, 2));
      assertEquals(11, file.size());
      assertThrows(IllegalStateException.class, () -> client.login("bob", "only-test"));
    }
  }
  @Test void clientShutdownDiscardsUnclosedWritesAndReleasesLease() throws Exception {
    var file = client.open("/file", "rw"); file.write(0, new byte[] {1});
    client.close(); client = null;
    assertEquals("hello world", new String(fixture.current, UTF_8));
    assertEquals("", fixture.lease);
    assertThrows(IllegalStateException.class, file::size);
  }
  @Test void writingPastEofExtendsFileAndPreservesPrefix() throws Exception {
    try (var file = client.open("/file", "rw")) { file.write(12, new byte[] {33}); }
    byte[] bytes = client.read("/file", 0, 100);
    assertEquals(13, bytes.length); assertEquals(0, bytes[11]); assertEquals(33, bytes[12]);
    assertEquals("hello world", new String(Arrays.copyOf(bytes, 11), UTF_8));
  }

  private static final class ProtocolFixture {
    volatile String endpoint, lease = "";
    volatile String version = UUID.randomUUID().toString(), block = UUID.randomUUID().toString();
    volatile byte[] current = "hello world".getBytes(UTF_8);
    volatile int commits;
    final Map<String, byte[]> blocks = new ConcurrentHashMap<>();
    final Map<String, AllocateFileResponse> plans = new ConcurrentHashMap<>();
    final Map<String, AllocateFileRequest> requests = new ConcurrentHashMap<>();

    static <T> void reply(StreamObserver<T> out, T value) { out.onNext(value); out.onCompleted(); }
    static void reject(StreamObserver<?> out) { out.onError(io.grpc.Status.FAILED_PRECONDITION.withDescription("version o lease invalido").asRuntimeException()); }

    final ControlServiceGrpc.ControlServiceImplBase control = new ControlServiceGrpc.ControlServiceImplBase() {
      @Override public void login(LoginRequest req, StreamObserver<LoginResponse> out) {
        reply(out, LoginResponse.newBuilder().setAccessToken("fixture-session").build());
      }
      @Override public void getFileManifest(FileRef req, StreamObserver<FileManifest> out) {
        blocks.putIfAbsent(block, current);
        reply(out, FileManifest.newBuilder().setFile(FileRef.newBuilder().setFileId(version).setPath("/file"))
            .setFileSizeBytes(current.length).addBlocks(BlockPlan.newBuilder().setBlockId(block)
                .setSizeBytes(current.length).setChecksumSha256(Hash.sha256(current))
                .addReplicas(BlockLocation.newBuilder().setDatanodeEndpoint(endpoint).setReadToken("fixture-read"))).build());
      }
      @Override public void acquireLock(LockRequest req, StreamObserver<LockResponse> out) {
        if (!lease.isEmpty()) { reject(out); return; }
        lease = UUID.randomUUID().toString(); reply(out, LockResponse.newBuilder().setLockId(lease).build());
      }
      @Override public void releaseLock(LockRef req, StreamObserver<co.eafit.dfsha.grpc.v1.Status> out) {
        lease = ""; reply(out, co.eafit.dfsha.grpc.v1.Status.newBuilder().setOk(true).build());
      }
      @Override public void allocateFile(AllocateFileRequest req, StreamObserver<AllocateFileResponse> out) {
        if (!version.equals(req.getReplacesFileId()) || !lease.equals(req.getLockToken())) { reject(out); return; }
        var plan = AllocateFileResponse.newBuilder().setFile(FileRef.newBuilder().setFileId(UUID.randomUUID().toString()).setPath(req.getPath()))
            .addBlocks(BlockPlan.newBuilder().setBlockId(UUID.randomUUID().toString()).setSizeBytes(req.getFileSizeBytes())
                .setDatanodeEndpoint(endpoint).setWriteToken("fixture-write")).build();
        plans.put(plan.getFile().getFileId(), plan); requests.put(plan.getFile().getFileId(), req); reply(out, plan);
      }
      @Override public void commitFile(CommitFileRequest req, StreamObserver<CommitFileResponse> out) {
        var plan = plans.get(req.getFileId()); var allocation = requests.get(req.getFileId());
        if (plan == null || !version.equals(allocation.getReplacesFileId()) || !lease.equals(allocation.getLockToken())) { reject(out); return; }
        version = plan.getFile().getFileId(); block = plan.getBlocks(0).getBlockId(); current = blocks.get(block); commits++;
        reply(out, CommitFileResponse.newBuilder().setStatus(co.eafit.dfsha.grpc.v1.Status.newBuilder().setOk(true)).build());
      }
    };
    final DataNodeServiceGrpc.DataNodeServiceImplBase data = new DataNodeServiceGrpc.DataNodeServiceImplBase() {
      @Override public void readBlock(ReadBlockRequest req, StreamObserver<DataChunk> out) {
        byte[] bytes = blocks.get(req.getBlockId());
        int from = (int) req.getOffsetBytes();
        int end = req.getLengthBytes() == 0 ? bytes.length : Math.min(bytes.length, from + (int) req.getLengthBytes());
        reply(out, DataChunk.newBuilder().setData(ByteString.copyFrom(bytes, from, end - from)).setLast(true).build());
      }
      @Override public StreamObserver<BlockChunk> writeBlock(StreamObserver<BlockWriteResponse> out) {
        return new StreamObserver<>() {
          String id; final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
          @Override public void onNext(BlockChunk c) { if (c.hasHeader()) id = c.getHeader().getBlockId(); else buffer.writeBytes(c.getData().toByteArray()); }
          @Override public void onError(Throwable t) { }
          @Override public void onCompleted() {
            byte[] bytes = buffer.toByteArray(); blocks.put(id, bytes);
            reply(out, BlockWriteResponse.newBuilder().setBytesWritten(bytes.length).setChecksumSha256(Hash.sha256(bytes))
                .setStatus(co.eafit.dfsha.grpc.v1.Status.newBuilder().setOk(true)).build());
          }
        };
      }
    };
  }
}
