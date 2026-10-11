package co.eafit.dfsha.common;

import io.grpc.BindableService;
import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptor;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Servers {

    private static final Logger log = LoggerFactory.getLogger(Servers.class);
    private static final int MAX_INBOUND_MESSAGE_SIZE = 16 * 1024 * 1024; // 16 MiB

    private Servers() {}

    /** Arma el servidor (todavía no lo arranca). */
    public static Server create(int port,
                                List<ServerInterceptor> interceptors,
                                BindableService... services) {
        ServerBuilder<?> builder =
                Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create());
        builder.maxInboundMessageSize(MAX_INBOUND_MESSAGE_SIZE);
        // Ojo: el último interceptor agregado es el primero en ejecutarse.
        for (ServerInterceptor i : interceptors) builder.intercept(i);
        for (BindableService s : services) builder.addService(s);
        return builder.build();
    }

    /** Arranca, registra el apagado ordenado y bloquea hasta que el servidor termine. */
    public static void startAndBlock(Server server) throws IOException, InterruptedException {
        server.start();
        log.info("Servidor gRPC escuchando en el puerto {}", server.getPort());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.err.println("Apagando servidor gRPC...");
            try {
                server.shutdown();
                if (!server.awaitTermination(30, TimeUnit.SECONDS)) {
                    server.shutdownNow();
                }
            } catch (InterruptedException e) {
                server.shutdownNow();
            }
        }));

        server.awaitTermination();
    }
}