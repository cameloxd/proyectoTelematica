package co.eafit.dfsha.common;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.eafit.dfsha.grpc.v1.ControlServiceGrpc;
import co.eafit.dfsha.grpc.v1.ListResponse;
import co.eafit.dfsha.grpc.v1.LoginRequest;
import co.eafit.dfsha.grpc.v1.LoginResponse;
import co.eafit.dfsha.grpc.v1.PathRequest;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GrpcPlumbingTest {

    private Server server;
    private ManagedChannel channel;
    private ControlServiceGrpc.ControlServiceBlockingStub stub;

    // Lo que el servicio falso "ve" dentro de la llamada
    private final AtomicReference<String> seenUser = new AtomicReference<>();
    private final AtomicReference<String> seenRequestId = new AtomicReference<>();
    // Token que usa el cliente (null = todavía no hizo login)
    private final AtomicReference<String> token = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        var fake = new ControlServiceGrpc.ControlServiceImplBase() {
            @Override
            public void login(LoginRequest req, StreamObserver<LoginResponse> out) {
                out.onNext(LoginResponse.getDefaultInstance());
                out.onCompleted();
            }

            @Override
            public void listDirectory(PathRequest req, StreamObserver<ListResponse> out) {
                seenUser.set(Ctx.USER_ID.get());
                seenRequestId.set(Ctx.REQUEST_ID.get());
                out.onNext(ListResponse.getDefaultInstance());
                out.onCompleted();
            }
        };

        // Validador falso: solo acepta el token "abc" y lo asocia a "user-1"
        TokenValidator validator =
                t -> "abc".equals(t) ? Optional.of("user-1") : Optional.empty();
        Set<String> publicMethods =
                Set.of(ControlServiceGrpc.getLoginMethod().getFullMethodName());

        server = Servers.create(0,   // puerto 0 = gRPC elige uno libre
                List.of(new AuthServerInterceptor(validator, publicMethods),
                        new RequestIdInterceptor()),
                fake).start();

        channel = Channels.create("localhost", server.getPort(),
                List.of(new AuthClientInterceptor(token::get)));
        stub = ControlServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() {
        Channels.shutdown(channel);
        server.shutdownNow();
    }

    @Test
    void login_funciona_sin_token() {
        assertDoesNotThrow(() -> stub.login(LoginRequest.getDefaultInstance()));
    }

    @Test
    void metodo_protegido_sin_token_da_UNAUTHENTICATED() {
        var ex = assertThrows(StatusRuntimeException.class,
                () -> stub.listDirectory(PathRequest.getDefaultInstance()));
        assertEquals(Status.Code.UNAUTHENTICATED, ex.getStatus().getCode());
    }

    @Test
    void token_malo_da_UNAUTHENTICATED() {
        token.set("malo");
        var ex = assertThrows(StatusRuntimeException.class,
                () -> stub.listDirectory(PathRequest.getDefaultInstance()));
        assertEquals(Status.Code.UNAUTHENTICATED, ex.getStatus().getCode());
    }

    @Test
    void token_bueno_pasa_y_el_servicio_ve_el_user_id() {
        token.set("abc");
        stub.listDirectory(PathRequest.getDefaultInstance());
        assertEquals("user-1", seenUser.get());
    }

    @Test
    void request_id_se_genera_si_el_cliente_no_lo_manda() {
        token.set("abc");
        stub.listDirectory(PathRequest.getDefaultInstance());
        assertNotNull(seenRequestId.get());
        assertDoesNotThrow(() -> UUID.fromString(seenRequestId.get()));
    }

    @Test
    void request_id_enviado_por_el_cliente_llega_igual_al_servicio() {
        token.set("abc");
        Metadata md = new Metadata();
        md.put(Headers.REQUEST_ID, "mi-id-123");
        stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(md))
            .listDirectory(PathRequest.getDefaultInstance());
        assertEquals("mi-id-123", seenRequestId.get());
    }
}