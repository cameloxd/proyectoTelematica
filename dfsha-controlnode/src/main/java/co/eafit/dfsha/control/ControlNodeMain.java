package co.eafit.dfsha.control;

import co.eafit.dfsha.common.Env;
import co.eafit.dfsha.common.AuthServerInterceptor;
import co.eafit.dfsha.common.RequestIdInterceptor;
import co.eafit.dfsha.common.Servers;
import co.eafit.dfsha.common.TokenValidator;
import co.eafit.dfsha.control.db.Database;
import co.eafit.dfsha.control.repository.JdbcFileFlowRepository;
import co.eafit.dfsha.control.repository.JdbcUserRepository;
import co.eafit.dfsha.control.repository.JdbcDataNodeRepository;
import co.eafit.dfsha.control.security.BlockTokenSigner;
import co.eafit.dfsha.control.service.AllocationPolicy;
import co.eafit.dfsha.control.service.ControlGrpcService;
import co.eafit.dfsha.control.service.NodeRegistryGrpcService;
import co.eafit.dfsha.grpc.v1.ControlServiceGrpc;
import co.eafit.dfsha.grpc.v1.NodeRegistryServiceGrpc;
import io.grpc.Server;
import java.util.List;
import java.util.Set;

/**
 * Servidor del ControlNode (metadatos). Responsable: Camilo.
 * Referencia del bootstrap en memoria: docs/reference/ControlNodeMain.bootstrap.java.txt
 */
public final class ControlNodeMain {
  public static void main(String[] args) throws Exception {
    int port = Env.getInt("GRPC_PORT", 50051);
    int blockSize = checkedBlockSize();
    Database database = Database.fromEnvironment();
    JdbcFileFlowRepository fileFlows = new JdbcFileFlowRepository(database.dataSource(),
        new AllocationPolicy(), Env.getLong("RESERVED_BYTES", 268435456L),
        Env.getInt("MAX_TRANSFERS_PER_NODE", 8), Env.getLong("TARGET_BLOCKS_PER_NODE", 1000));
    ControlGrpcService control = new ControlGrpcService(fileFlows,
        new JdbcUserRepository(database.dataSource()), BlockTokenSigner.fromEnvironment(), blockSize,
        Env.getLong("PENDING_FILE_TTL_SECONDS", 3600));
    NodeRegistryGrpcService registry = new NodeRegistryGrpcService(
        new JdbcDataNodeRepository(database.dataSource()), fileFlows);
    Set<String> publicMethods = Set.of(ControlServiceGrpc.getLoginMethod().getFullMethodName(),
        NodeRegistryServiceGrpc.getRegisterDataNodeMethod().getFullMethodName(),
        NodeRegistryServiceGrpc.getReportBlockStoredMethod().getFullMethodName());
    Server server = Servers.create(port, List.of(new RequestIdInterceptor(),
        new AuthServerInterceptor(TokenValidator.permissiveForDev(), publicMethods)), control, registry);
    Runtime.getRuntime().addShutdownHook(new Thread(database::close));
    Servers.startAndBlock(server);
  }

  private static int checkedBlockSize() {
    long size=Env.getLong("DFS_BLOCK_SIZE_BYTES",8388608L);
    long min=Env.getLong("DFS_MIN_BLOCK_SIZE_BYTES",1048576L);
    long max=Env.getLong("DFS_MAX_BLOCK_SIZE_BYTES",67108864L);
    if(size<min||size>max||size>Integer.MAX_VALUE)throw new IllegalArgumentException("DFS_BLOCK_SIZE_BYTES fuera de rango");
    return (int)size;
  }
}
