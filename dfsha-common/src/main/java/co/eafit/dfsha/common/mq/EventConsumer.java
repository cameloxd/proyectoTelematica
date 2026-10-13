package co.eafit.dfsha.common.mq;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DeliverCallback;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EventConsumer {

    private static final Logger log = LoggerFactory.getLogger(EventConsumer.class);

    @FunctionalInterface
    public interface Handler {
        void handle(byte[] body) throws Exception;
    }

    private final Channel channel;

    public EventConsumer(RabbitConnection connection) throws IOException {
        this.channel = connection.createChannel();
        this.channel.basicQos(10);   // máximo 10 mensajes sin confirmar a la vez
    }

    public void subscribe(String queue, Handler handler) throws IOException {
        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            long tag = delivery.getEnvelope().getDeliveryTag();
            try {
                handler.handle(delivery.getBody());
                channel.basicAck(tag, false);          // todo bien: confirmar
            } catch (Exception e) {
                log.error("Falló el procesamiento de un mensaje de {}: {}", queue, e.getMessage(), e);
                channel.basicNack(tag, false, false);  // sin reencolar: cae en la cola de fallidos
            }
        };
        channel.basicConsume(queue, false, deliverCallback, consumerTag -> {});
    }
}