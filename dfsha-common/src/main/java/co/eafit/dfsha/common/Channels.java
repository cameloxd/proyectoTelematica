package co.eafit.dfsha.common;

import io.grpc.ClientInterceptor;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Channels {

    private static final Logger log = LoggerFactory.getLogger(Channels.class);

    // Mismo límite que en Servers; los bloques son de 8 MiB.
    private static final int MAX_MESSAGE_BYTES = 16 * 1024 * 1024;

    private Channels() {}

    /**
     * Crea un canal hacia host:port con los interceptores del cliente ya aplicados.
     * Un canal es pesado y thread-safe: créalo una vez por destino y reutilízalo.
     */
    public static ManagedChannel create(String host, int port, List<ClientInterceptor> interceptors) {
        // Plaintext por ahora; TLS es de Hito 3 y se cambia solo aquí.
        ManagedChannelBuilder<?> builder = Grpc.newChannelBuilderForAddress(
                host, port, InsecureChannelCredentials.create());
        builder.maxInboundMessageSize(MAX_MESSAGE_BYTES);
        // Ojo: el último interceptor agregado es el primero en ejecutarse.
        for (ClientInterceptor i : interceptors) builder.intercept(i);
        log.info("Canal gRPC creado hacia {}:{}", host, port);
        return builder.build();
    }

    /** Sobrecarga sin interceptores (pruebas o llamadas públicas como Login). */
    public static ManagedChannel create(String host, int port) {
        return create(host, port, List.of());
    }

    /** Cierre ordenado: espera 5 s a que terminen las llamadas y luego fuerza. */
    public static void shutdown(ManagedChannel channel) {
        channel.shutdown();
        try {
            if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                channel.shutdownNow();
            }
        } catch (InterruptedException e) {
            channel.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}