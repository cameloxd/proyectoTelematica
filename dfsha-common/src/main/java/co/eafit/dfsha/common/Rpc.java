package co.eafit.dfsha.common;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ChannelCredentials;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.ForwardingClientCall;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.InsecureServerCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerCredentials;
import io.grpc.ServerInterceptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.TlsChannelCredentials;
import io.grpc.TlsServerCredentials;
import io.grpc.stub.StreamObserver;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Todo lo de gRPC en un solo lugar: servidores y canales (con TLS), autenticación con token Bearer,
 * failover entre ControlNodes y una forma única de responder errores.
 */
public final class Rpc {
  private static final Logger log = LoggerFactory.getLogger(Rpc.class);
  private static final int MAX_MESSAGE_BYTES = 16 * 1024 * 1024;   // los bloques viajan en trozos de 1 MiB

  /** Aquí deja el interceptor del servidor el user_id del token, para que el servicio sepa quién llama. */
  public static final Context.Key<String> USER_ID = Context.key("user_id");
  private static final Metadata.Key<String> AUTHORIZATION =
      Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

  private Rpc() { }

  // ------------------------------------------------------------------ servidores y canales

  /** Arranca un servidor gRPC. Con TLS_CERT_FILE y TLS_KEY_FILE usa TLS; sin ellos, texto plano. */
  public static Server server(int port, ServerServiceDefinition... services) throws IOException {
    String cert = Env.get("TLS_CERT_FILE", null);
    String key = Env.get("TLS_KEY_FILE", null);
    ServerCredentials credentials = cert != null && key != null
        ? TlsServerCredentials.create(new File(cert), new File(key))
        : InsecureServerCredentials.create();
    ServerBuilder<?> builder = Grpc.newServerBuilderForPort(port, credentials)
        .maxInboundMessageSize(MAX_MESSAGE_BYTES);
    for (ServerServiceDefinition service : services) builder.addService(service);
    Server server = builder.build().start();
    log.info("Servidor gRPC en el puerto {} ({})", port, cert != null ? "TLS" : "SIN TLS");
    return server;
  }

  /** Canal hacia "host:puerto". Con CA configurada usa TLS y verifica el certificado del servidor. */
  public static ManagedChannel channel(String endpoint, ClientInterceptor... interceptors) {
    String ca = caFile();
    try {
      ChannelCredentials credentials = ca != null
          ? TlsChannelCredentials.newBuilder().trustManager(new File(ca)).build()
          : InsecureChannelCredentials.create();
      return Grpc.newChannelBuilder(endpoint, credentials)
          .maxInboundMessageSize(MAX_MESSAGE_BYTES)
          .intercept(interceptors)
          .build();
    } catch (IOException e) {
      throw new UncheckedIOException("No pude leer la CA " + ca, e);
    }
  }

  /** TLS_CA_FILE; si no está definida se usa deploy/secrets/ca.crt cuando existe. "none" = sin TLS. */
  private static String caFile() {
    String fallback = Files.exists(Path.of("deploy/secrets/ca.crt")) ? "deploy/secrets/ca.crt" : null;
    String ca = Env.get("TLS_CA_FILE", fallback);
    return "none".equals(ca) ? null : ca;
  }

  // ------------------------------------------------------------------ autenticación

  /**
   * Interceptor del servidor: exige "authorization: Bearer &lt;token&gt;" en todos los métodos salvo
   * {@code publicMethods}. {@code validator} recibe el token y devuelve el user_id, o null si no sirve.
   */
  public static ServerInterceptor requireToken(Function<String, String> validator, Set<String> publicMethods) {
    return new ServerInterceptor() {
      @Override
      public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                         ServerCallHandler<Q, R> next) {
        if (publicMethods.contains(call.getMethodDescriptor().getFullMethodName())) {
          return next.startCall(call, headers);
        }
        String auth = headers.get(AUTHORIZATION);
        String userId = auth != null && auth.startsWith("Bearer ") ? validator.apply(auth.substring(7)) : null;
        if (userId == null) {
          call.close(Status.UNAUTHENTICATED.withDescription("token ausente o vencido: haz login"), new Metadata());
          return new ServerCall.Listener<>() { };
        }
        return Contexts.interceptCall(Context.current().withValue(USER_ID, userId), call, headers, next);
      }
    };
  }

  /** Interceptor del cliente: agrega el token Bearer en cada llamada (cuando ya hay uno). */
  public static ClientInterceptor bearerToken(Supplier<String> token) {
    return new ClientInterceptor() {
      @Override
      public <Q, R> ClientCall<Q, R> interceptCall(MethodDescriptor<Q, R> method, CallOptions options, Channel next) {
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, options)) {
          @Override
          public void start(Listener<R> listener, Metadata headers) {
            String value = token.get();
            if (value != null) headers.put(AUTHORIZATION, "Bearer " + value);
            super.start(listener, headers);
          }
        };
      }
    };
  }

  // ------------------------------------------------------------------ failover

  /** Varios servidores equivalentes (los ControlNodes): si el actual no responde, se usa el siguiente. */
  public static final class Failover implements AutoCloseable {
    private final List<String> endpoints;
    private final List<ManagedChannel> channels = new ArrayList<>();
    private volatile int current = 0;

    public Failover(List<String> endpoints, ClientInterceptor... interceptors) {
      this.endpoints = endpoints;
      for (String endpoint : endpoints) channels.add(channel(endpoint, interceptors));
    }

    /** Ejecuta la llamada. Si el servidor está caído (UNAVAILABLE) la repite en el siguiente. */
    public <T> T call(Function<Channel, T> rpc) {
      StatusRuntimeException last = null;
      int start = current;
      for (int attempt = 0; attempt < channels.size(); attempt++) {
        int i = (start + attempt) % channels.size();
        try {
          T result = rpc.apply(channels.get(i));
          if (i != current) log.warn("Cambio de ControlNode: ahora uso {}", endpoints.get(i));
          current = i;
          return result;
        } catch (StatusRuntimeException e) {
          if (e.getStatus().getCode() != Status.Code.UNAVAILABLE) throw e;   // error real: no reintentar
          log.warn("{} no responde ({})", endpoints.get(i), e.getStatus().getDescription());
          last = e;
        }
      }
      throw last;
    }

    @Override
    public void close() {
      channels.forEach(ManagedChannel::shutdownNow);
    }
  }

  // ------------------------------------------------------------------ respuestas y errores

  /**
   * Ejecuta la lógica de un rpc unario y responde. Cualquier excepción se convierte en un error gRPC:
   * así los servicios solo hacen {@code throw Rpc.error(Status.NOT_FOUND, "...")}.
   */
  public static <T> void respond(StreamObserver<T> out, Callable<T> work) {
    try {
      T result = work.call();
      out.onNext(result);
      out.onCompleted();
    } catch (StatusRuntimeException e) {
      out.onError(e);
    } catch (SQLException e) {
      if ("23505".equals(e.getSQLState())) {            // violación de UNIQUE en PostgreSQL
        out.onError(error(Status.ALREADY_EXISTS, "ya existe"));
      } else {
        log.error("Error de base de datos", e);
        out.onError(error(Status.INTERNAL, "error de base de datos (ver log del ControlNode)"));
      }
    } catch (Exception e) {
      log.error("Error inesperado", e);
      out.onError(error(Status.INTERNAL, e.toString()));
    }
  }

  public static StatusRuntimeException error(Status status, String message) {
    return status.withDescription(message).asRuntimeException();
  }

  /** Respuesta de éxito del contrato (el Status del .proto, no el de gRPC). */
  public static co.eafit.dfsha.grpc.v1.Status ok(String message) {
    return co.eafit.dfsha.grpc.v1.Status.newBuilder().setOk(true).setCode("OK").setMessage(message).build();
  }
}
