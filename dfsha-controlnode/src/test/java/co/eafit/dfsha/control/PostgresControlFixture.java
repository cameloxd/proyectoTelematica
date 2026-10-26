package co.eafit.dfsha.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.eafit.dfsha.common.Rpc;
import co.eafit.dfsha.common.Tokens;
import co.eafit.dfsha.grpc.v1.AllocateFileRequest;
import co.eafit.dfsha.grpc.v1.AllocateFileResponse;
import co.eafit.dfsha.grpc.v1.BlockPlan;
import co.eafit.dfsha.grpc.v1.CommitFileRequest;
import co.eafit.dfsha.grpc.v1.CommitFileResponse;
import co.eafit.dfsha.grpc.v1.ControlServiceGrpc;
import co.eafit.dfsha.grpc.v1.FileManifest;
import co.eafit.dfsha.grpc.v1.FileRef;
import co.eafit.dfsha.grpc.v1.LoginRequest;
import co.eafit.dfsha.grpc.v1.LoginResponse;
import co.eafit.dfsha.grpc.v1.NodeRegistryServiceGrpc;
import co.eafit.dfsha.grpc.v1.PathRequest;
import co.eafit.dfsha.grpc.v1.RegisterDataNodeRequest;
import co.eafit.dfsha.grpc.v1.RegisterDataNodeResponse;
import co.eafit.dfsha.grpc.v1.ReportBlockStoredRequest;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.grpc.InsecureChannelCredentials;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/** PostgreSQL y gRPC reales; nunca abre RabbitMQ ni un DataNode de almacenamiento. */
abstract class PostgresControlFixture {
  static final long MIB = 1024L * 1024;
  static final long BLOCK = 8 * MIB;
  static final String CHECKSUM = "ab".repeat(32);
  static final String REGISTRATION_KEY = "test-registration-only";

  Db db;
  Tokens tokens;
  String schema;
  LoginResponse alice;
  LoginResponse bob;
  ControlServiceGrpc.ControlServiceBlockingStub publicControl;
  NodeRegistryServiceGrpc.NodeRegistryServiceBlockingStub registry;
  final Map<String, RegisterDataNodeResponse> nodes = new HashMap<>();
  private ManagedChannel channel;
  private Server server;
  private boolean schemaCreated;

  @BeforeEach
  void setUpPostgres() throws Exception {
    // UUID generado localmente: DROP SCHEMA solo se aplica al esquema creado por ESTA prueba.
    schema = "dfsha_c2_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection c = adminConnection(); var s = c.createStatement()) {
      s.execute("CREATE SCHEMA " + schema);
      schemaCreated = true;
      c.setSchema(schema);
      s.execute(Files.readString(Path.of("..", "deploy", "schema.sql")));
      s.execute(Files.readString(Path.of("..", "deploy", "migrations", "001-block-delete-outbox.sql")));
      s.execute(Files.readString(Path.of("..", "deploy", "migrations", "002-file-versions.sql")));
    }
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(System.getenv("DFSHA_TEST_DB_URL"));
    config.setDataSourceProperties(credentials());
    config.addDataSourceProperty("ApplicationName", schema);
    config.setConnectionInitSql("SET search_path TO " + schema);
    config.setMaximumPoolSize(6);
    config.setMinimumIdle(0);
    config.setConnectionTimeout(5000);
    config.setPoolName(schema);
    db = new Db(new HikariDataSource(config));
    var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    tokens = new Tokens(pair.getPrivate(), pair.getPublic());
    // Los RPC solo persisten tareas. No abren RabbitMQ ni certifican un broker real.
    var control = new ControlServiceImpl(db, tokens);
    var auth = Rpc.requireToken(t -> tokens.verify(t, Tokens.SESSION), Set.of(
        ControlServiceGrpc.getRegisterMethod().getFullMethodName(),
        ControlServiceGrpc.getLoginMethod().getFullMethodName()));
    server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
        .addService(ServerInterceptors.intercept(control, auth))
        .addService(new NodeRegistryImpl(db, REGISTRATION_KEY)).build().start();
    channel = Grpc.newChannelBuilderForAddress("127.0.0.1", server.getPort(), InsecureChannelCredentials.create())
        .build();
    publicControl = ControlServiceGrpc.newBlockingStub(channel).withDeadlineAfter(15, TimeUnit.SECONDS);
    registry = NodeRegistryServiceGrpc.newBlockingStub(channel);
    alice = user("alice");
    bob = user("bob");
  }

  @AfterEach
  void tearDownPostgres() throws Exception {
    try {
      if (channel != null) channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      if (server != null) server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    } finally {
      try {
        if (db != null) db.close();
      } finally {
        if (schemaCreated) {
          try (Connection c = adminConnection(); var s = c.createStatement()) {
            s.execute("DROP SCHEMA " + schema + " CASCADE");
          }
        }
      }
    }
  }

  private static Properties credentials() {
    Properties p = new Properties();
    p.setProperty("user", System.getenv().getOrDefault("DFSHA_TEST_DB_USER", "postgres"));
    p.setProperty("password", System.getenv().getOrDefault("DFSHA_TEST_DB_PASSWORD", ""));
    return p;
  }

  Connection adminConnection() throws SQLException {
    return DriverManager.getConnection(System.getenv("DFSHA_TEST_DB_URL"), credentials());
  }

  private LoginResponse user(String username) {
    LoginRequest req = LoginRequest.newBuilder().setUsername(username).setPassword("test-password-only").build();
    assertTrue(publicControl.withDeadlineAfter(10, TimeUnit.SECONDS).register(req).getOk());
    return publicControl.withDeadlineAfter(10, TimeUnit.SECONDS).login(req);
  }

  ControlServiceGrpc.ControlServiceBlockingStub as(LoginResponse user) {
    return ControlServiceGrpc.newBlockingStub(channel)
        .withInterceptors(Rpc.bearerToken(user::getAccessToken)).withDeadlineAfter(10, TimeUnit.SECONDS);
  }

  RegisterDataNodeResponse node(String name, long capacity) {
    RegisterDataNodeResponse response = registry.withDeadlineAfter(10, TimeUnit.SECONDS).registerDataNode(
        RegisterDataNodeRequest.newBuilder().setNodeName(name).setCapacityBytes(capacity)
            .setAdvertisedEndpoint(name + ":50052").setInternalEndpoint(name + ":50053")
            .setRegistrationKey(REGISTRATION_KEY).build());
    assertTrue(response.getStatus().getOk());
    nodes.put(response.getDatanodeId(), response);
    return response;
  }

  AllocateFileResponse allocate(LoginResponse owner, String path, long bytes, String operation) {
    return as(owner).allocateFile(request(path, bytes, operation));
  }

  static AllocateFileRequest request(String path, long bytes, String operation) {
    return AllocateFileRequest.newBuilder().setPath(path).setFileSizeBytes(bytes).setOperationId(operation).build();
  }

  CommitFileResponse commit(LoginResponse owner, AllocateFileResponse plan) {
    return as(owner).commitFile(CommitFileRequest.newBuilder().setFileId(plan.getFile().getFileId()).build());
  }

  FileManifest manifest(LoginResponse owner, String path) {
    return as(owner).getFileManifest(FileRef.newBuilder().setPath(path).build());
  }

  static PathRequest path(String path) {
    return PathRequest.newBuilder().setPath(path).build();
  }

  ReportBlockStoredRequest report(BlockPlan block) {
    return ReportBlockStoredRequest.newBuilder().setBlockId(block.getBlockId())
        .setDatanodeId(block.getDatanodeId()).setNodeToken(nodes.get(block.getDatanodeId()).getNodeToken())
        .setBytesWritten(block.getSizeBytes()).setChecksumSha256(CHECKSUM).build();
  }

  void stored(BlockPlan block) {
    assertTrue(registry.withDeadlineAfter(10, TimeUnit.SECONDS).reportBlockStored(report(block)).getOk());
  }

  long scalar(String sql, Object... params) throws SQLException {
    return db.tx(c -> Db.one(c, sql, r -> r.getLong(1), params));
  }

  String text(String sql, Object... params) throws SQLException {
    return db.tx(c -> Db.one(c, sql, r -> r.getString(1), params));
  }

  void update(String sql, Object... params) throws SQLException {
    db.tx(c -> Db.update(c, sql, params));
  }

  long reserved() throws SQLException {
    return scalar("SELECT COALESCE(SUM(b.size_bytes), 0) FROM block_location l "
        + "JOIN dfs_block b USING (block_id) WHERE l.state = 'PLANNED'");
  }

  static void fails(Status.Code code, Runnable call) {
    StatusRuntimeException error = assertThrows(StatusRuntimeException.class, call::run);
    assertEquals(code, error.getStatus().getCode(), error.getMessage());
  }

  static UUID id(String value) {
    return UUID.fromString(value);
  }
}
