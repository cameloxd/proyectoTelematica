package co.eafit.dfsha.common.mq;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import java.io.IOException;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RabbitConnection implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RabbitConnection.class);

    private final Connection connection;

    private RabbitConnection(Connection connection) {
        this.connection = connection;
    }

    /** Conecta con 10 intentos, 3 s entre cada uno. */
    public static RabbitConnection connect(RabbitConfig config, String connectionName) {
        return connect(config, connectionName, 10, 3000);
    }

    public static RabbitConnection connect(RabbitConfig config, String connectionName,
                                           int maxAttempts, long delayMillis) {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(config.host());
        factory.setPort(config.port());
        factory.setUsername(config.username());
        factory.setPassword(config.password());
        factory.setAutomaticRecoveryEnabled(true);   // reconecta si se cae después
        factory.setTopologyRecoveryEnabled(true);    // vuelve a declarar colas y bindings
        factory.setNetworkRecoveryInterval(5000);    // reintenta cada 5 s

        // La recuperación automática NO cubre el primer intento: por eso este bucle.
        for (int attempt = 1; ; attempt++) {
            try {
                Connection connection = factory.newConnection(connectionName);
                log.info("Conectado a RabbitMQ {} como '{}'", config, connectionName);
                return new RabbitConnection(connection);
            } catch (IOException | TimeoutException e) {
                if (attempt >= maxAttempts) {
                    throw new IllegalStateException(
                            "No pude conectar a RabbitMQ " + config + " tras " + attempt + " intentos", e);
                }
                log.warn("RabbitMQ no disponible (intento {}/{}): {}", attempt, maxAttempts, e.getMessage());
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrumpido mientras esperaba a RabbitMQ", ie);
                }
            }
        }
    }

    public Channel createChannel() throws IOException {
        return connection.createChannel();
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (IOException e) {
            log.warn("Error cerrando la conexión a RabbitMQ: {}", e.getMessage());
        }
    }
}
