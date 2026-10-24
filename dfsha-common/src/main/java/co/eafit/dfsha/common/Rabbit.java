package co.eafit.dfsha.common;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.MessageProperties;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Comunicación asíncrona por RabbitMQ. Todo se publica en el exchange "dfsha.events" (tipo topic):
 *
 * <pre>
 *   heartbeat.&lt;datanode&gt;    -&gt; cola dfsha.heartbeat              (la consumen los ControlNodes)
 *   replication.&lt;datanode&gt;  -&gt; cola dfsha.replication.&lt;datanode&gt;  (la consume ese DataNode)
 *   delete.&lt;datanode&gt;       -&gt; cola dfsha.delete.&lt;datanode&gt;       (la consume ese DataNode)
 * </pre>
 *
 * Si un DataNode no puede procesar una tarea, el mensaje va a dfsha.replication.failed o
 * dfsha.delete.failed (dead-letter), donde se puede revisar en la consola de RabbitMQ.
 */
public final class Rabbit implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(Rabbit.class);

  public static final String EXCHANGE = "dfsha.events";
  public static final String HEARTBEAT_QUEUE = "dfsha.heartbeat";
  private static final String FAILED_EXCHANGE = "dfsha.events.failed";

  private final Connection connection;
  private final Channel publisher;

  public static String heartbeatKey(String nodeId) { return "heartbeat." + nodeId; }
  public static String replicationKey(String nodeId) { return "replication." + nodeId; }
  public static String deleteKey(String nodeId) { return "delete." + nodeId; }
  public static String replicationQueue(String nodeId) { return "dfsha.replication." + nodeId; }
  public static String deleteQueue(String nodeId) { return "dfsha.delete." + nodeId; }

  /** Conecta usando RABBITMQ_HOST, RABBITMQ_PORT, RABBITMQ_USER y RABBITMQ_PASSWORD(_FILE). */
  public static Rabbit connect(String clientName) {
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(Env.get("RABBITMQ_HOST", "localhost"));
    factory.setPort(Env.getInt("RABBITMQ_PORT", 5672));
    factory.setUsername(Env.get("RABBITMQ_USER", "guest"));
    factory.setPassword(Env.secret("RABBITMQ_PASSWORD", "guest"));
    factory.setAutomaticRecoveryEnabled(true);   // si RabbitMQ se reinicia, se reconecta solo
    for (int attempt = 1; ; attempt++) {
      try {
        return new Rabbit(factory.newConnection(clientName));
      } catch (IOException | TimeoutException e) {
        if (attempt == 20) throw new IllegalStateException("RabbitMQ no disponible", e);
        log.warn("RabbitMQ no disponible (intento {}/20), reintento en 3 s", attempt);
        sleep(3000);
      }
    }
  }

  private Rabbit(Connection connection) throws IOException {
    this.connection = connection;
    this.publisher = connection.createChannel();
    publisher.confirmSelect();                                   // RabbitMQ confirma cada publicación
    publisher.exchangeDeclare(EXCHANGE, "topic", true);
    publisher.exchangeDeclare(FAILED_EXCHANGE, "direct", true);
    // Un heartbeat de hace más de 30 s ya no sirve: se descarta solo.
    publisher.queueDeclare(HEARTBEAT_QUEUE, true, false, false, Map.<String, Object>of("x-message-ttl", 30000));
    publisher.queueBind(HEARTBEAT_QUEUE, EXCHANGE, "heartbeat.*");
    log.info("Conectado a RabbitMQ");
  }

  /** Declara las colas de trabajo de un DataNode (réplicas y borrados). Repetirlo no hace daño. */
  public synchronized void declareNodeQueues(String nodeId) throws IOException {
    declareWorkQueue(replicationQueue(nodeId), replicationKey(nodeId), "dfsha.replication.failed");
    declareWorkQueue(deleteQueue(nodeId), deleteKey(nodeId), "dfsha.delete.failed");
  }

  private void declareWorkQueue(String queue, String routingKey, String failedQueue) throws IOException {
    publisher.queueDeclare(failedQueue, true, false, false, null);
    publisher.queueBind(failedQueue, FAILED_EXCHANGE, failedQueue);
    publisher.queueDeclare(queue, true, false, false, Map.<String, Object>of(
        "x-dead-letter-exchange", FAILED_EXCHANGE,       // lo rechazado (nack) se va a la cola de fallidos
        "x-dead-letter-routing-key", failedQueue));
    publisher.queueBind(queue, EXCHANGE, routingKey);
  }

  /** Publica un mensaje. persistent=true para tareas: sobreviven a un reinicio de RabbitMQ. */
  public synchronized void publish(String routingKey, byte[] body, boolean persistent) throws Exception {
    publisher.basicPublish(EXCHANGE, routingKey, persistent ? MessageProperties.PERSISTENT_BASIC : null, body);
    if (persistent) publisher.waitForConfirmsOrDie(5000);
  }

  /** Lo que hace un consumidor con cada mensaje. Si lanza una excepción, el mensaje se rechaza. */
  public interface Handler {
    void handle(byte[] body) throws Exception;
  }

  /** Consume una cola: si el handler termina bien se confirma (ack); si falla, nack sin reencolar. */
  public void consume(String queue, Handler handler) throws IOException {
    Channel channel = connection.createChannel();
    channel.basicQos(1);                                         // un mensaje a la vez por consumidor
    channel.basicConsume(queue, false, (tag, delivery) -> {
      long id = delivery.getEnvelope().getDeliveryTag();
      try {
        handler.handle(delivery.getBody());
        channel.basicAck(id, false);
      } catch (Exception e) {
        log.error("Falló un mensaje de {}: {}", queue, e.toString());
        channel.basicNack(id, false, false);
      }
    }, tag -> { });
  }

  @Override
  public void close() {
    try {
      connection.close();
    } catch (Exception ignored) {
      // apagando
    }
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
