package co.eafit.dfsha.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.eafit.dfsha.grpc.v1.ControlServiceGrpc;
import co.eafit.dfsha.grpc.v1.ListResponse;
import co.eafit.dfsha.grpc.v1.LoginRequest;
import co.eafit.dfsha.grpc.v1.LoginResponse;
import co.eafit.dfsha.grpc.v1.PathRequest;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Levanta un servidor gRPC real en localhost y prueba el interceptor de autenticación y el failover. */
class RpcAuthTest {
  private Server server;
  private String token;            // lo que el cliente manda como Bearer
  private String userSeenByServer;

  @BeforeEach
  void setUp() throws Exception {
    var service = new ControlServiceGrpc.ControlServiceImplBase() {
      @Override
      public void login(LoginRequest request, StreamObserver<LoginResponse> out) {
        Rpc.respond(out, LoginResponse::getDefaultInstance);
      }

      @Override
      public void listDirectory(PathRequest request, StreamObserver<ListResponse> out) {
        Rpc.respond(out, () -> {
          userSeenByServer = Rpc.USER_ID.get();
          return ListResponse.getDefaultInstance();
        });
      }
    };
    var auth = Rpc.requireToken(t -> t.equals("abc") ? "user-1" : null,
        Set.of(ControlServiceGrpc.getLoginMethod().getFullMethodName()));
    server = Rpc.server(0, ServerInterceptors.intercept(service, auth));
  }

  @AfterEach
  void tearDown() {
    server.shutdownNow();
  }

  private ControlServiceGrpc.ControlServiceBlockingStub stub() {
    ManagedChannel channel = Rpc.channel("localhost:" + server.getPort(), Rpc.bearerToken(() -> token));
    return ControlServiceGrpc.newBlockingStub(channel);
  }

  @Test
  void loginEsPublico() {
    stub().login(LoginRequest.getDefaultInstance());
  }

  @Test
  void sinTokenDaUnauthenticated() {
    var e = assertThrows(StatusRuntimeException.class, () -> stub().listDirectory(PathRequest.getDefaultInstance()));
    assertEquals(Status.Code.UNAUTHENTICATED, e.getStatus().getCode());
  }

  @Test
  void conTokenElServicioVeElUsuario() {
    token = "abc";
    stub().listDirectory(PathRequest.getDefaultInstance());
    assertEquals("user-1", userSeenByServer);
  }

  @Test
  void failoverSaltaAlServidorQueSiResponde() {
    token = "abc";
    try (var failover = new Rpc.Failover(List.of("localhost:1", "localhost:" + server.getPort()),
        Rpc.bearerToken(() -> token))) {
      failover.call(ch -> ControlServiceGrpc.newBlockingStub(ch).listDirectory(PathRequest.getDefaultInstance()));
    }
    assertEquals("user-1", userSeenByServer);
  }
}
