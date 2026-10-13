package co.eafit.dfsha.common.mq;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.MessageProperties;
import java.io.IOException;
import java.util.concurrent.TimeoutException;

public final class EventPublisher {

    private final Channel channel;

    public EventPublisher(RabbitConnection connection) throws IOException {
        this.channel = connection.createChannel();
        this.channel.confirmSelect();   // RabbitMQ confirma que recibió el mensaje
    }

    /** persistent=true para borrado y auditoría; false para heartbeats. */
    public synchronized void publish(String routingKey, byte[] body, boolean persistent)
            throws IOException, InterruptedException, TimeoutException {
        AMQP.BasicProperties props = persistent ? MessageProperties.PERSISTENT_BASIC : null;
        channel.basicPublish(Topology.EXCHANGE, routingKey, props, body);
        if (persistent) {
            channel.waitForConfirmsOrDie(5000);   // si no confirma en 5 s, lanza excepción
        }
    }
}
