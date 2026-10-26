package co.eafit.dfsha.common;

import static org.junit.jupiter.api.Assertions.*;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Return;
import com.rabbitmq.client.ReturnCallback;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/** Contrato del adaptador AMQP con canal inyectado: NO es una prueba con RabbitMQ real. */
class RabbitDeletePublishTest {
  @Test
  void declaresDurableDestinationAndWaitsForItsMandatoryPersistentPublish() throws Exception {
    var amqp = new RecordingChannel();
    Rabbit.publishDelete(amqp.channel(), "node-1", new byte[] {1, 2});
    assertEquals(List.of("confirm", "queue:dfsha.delete.failed", "bind:dfsha.delete.failed",
        "queue:dfsha.delete.node-1", "bind:delete.node-1", "return-listener", "publish", "confirmed"), amqp.calls);
  }

  @Test
  void returnedMessageIsAFailureEvenIfTheBrokerConfirmsIt() {
    var amqp = new RecordingChannel();
    amqp.returned = true;
    assertThrows(IOException.class, () -> Rabbit.publishDelete(amqp.channel(), "node-1", new byte[] {1, 2}));
    assertTrue(amqp.calls.contains("confirmed"));
  }

  @Test
  void confirmationTimeoutIsPropagatedToTheOutbox() {
    var amqp = new RecordingChannel();
    amqp.failure = new TimeoutException("sin confirmación");
    assertThrows(TimeoutException.class, () -> Rabbit.publishDelete(amqp.channel(), "node-1", new byte[] {1, 2}));
  }

  @Test
  void negativeConfirmationIsPropagatedToTheOutbox() {
    var amqp = new RecordingChannel();
    amqp.failure = new IOException("nack");
    assertThrows(IOException.class, () -> Rabbit.publishDelete(amqp.channel(), "node-1", new byte[] {1, 2}));
  }

  private static final class RecordingChannel {
    final List<String> calls = new ArrayList<>();
    ReturnCallback returnCallback;
    boolean returned;
    Exception failure;

    Channel channel() {
      return (Channel) Proxy.newProxyInstance(Channel.class.getClassLoader(), new Class<?>[] {Channel.class},
          (proxy, method, args) -> {
            switch (method.getName()) {
              case "confirmSelect" -> calls.add("confirm");
              case "queueDeclare" -> {
                assertEquals(true, args[1]); // durable
                assertEquals(false, args[2]);
                assertEquals(false, args[3]);
                if (args[0].equals("dfsha.delete.node-1")) {
                  assertEquals(Map.of("x-dead-letter-exchange", "dfsha.events.failed",
                      "x-dead-letter-routing-key", "dfsha.delete.failed"), args[4]);
                }
                calls.add("queue:" + args[0]);
              }
              case "queueBind" -> calls.add("bind:" + args[2]);
              case "addReturnListener" -> {
                returnCallback = (ReturnCallback) args[0];
                calls.add("return-listener");
              }
              case "basicPublish" -> {
                assertEquals(5, args.length);
                assertEquals(Rabbit.EXCHANGE, args[0]);
                assertEquals("delete.node-1", args[1]);
                assertEquals(true, args[2]); // mandatory
                assertEquals(2, ((AMQP.BasicProperties) args[3]).getDeliveryMode());
                assertArrayEquals(new byte[] {1, 2}, (byte[]) args[4]);
                calls.add("publish");
              }
              case "waitForConfirmsOrDie" -> {
                assertEquals(5000L, args[0]);
                if (returned) returnCallback.handle(new Return(312, "NO_ROUTE", Rabbit.EXCHANGE,
                    "delete.node-1", null, new byte[0]));
                if (failure != null) throw failure;
                calls.add("confirmed");
              }
              default -> throw new AssertionError("Llamada AMQP inesperada: " + method.getName());
            }
            return null;
          });
    }
  }
}
