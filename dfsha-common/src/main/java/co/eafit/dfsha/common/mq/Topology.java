package co.eafit.dfsha.common.mq;

import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public final class Topology {

    public static final String EXCHANGE = "dfsha.events";
    public static final String DLX = "dfsha.events.dlx";
    public static final String HEARTBEAT_QUEUE = "dfsha.heartbeat";
    public static final String AUDIT_QUEUE = "dfsha.audit";
    public static final String DELETE_FAILED_QUEUE = "dfsha.block-delete.failed";

    private Topology() {}

    public static String heartbeatKey(String datanodeId) { return "heartbeat." + datanodeId; }
    public static String deleteKey(String datanodeId)    { return "block.delete." + datanodeId; }
    public static String auditKey(String action)         { return "audit." + action; }
    public static String deleteQueue(String datanodeId)  { return "dfsha.block-delete." + datanodeId; }

    /** Exchanges y colas fijas. Declarar dos veces es seguro. */
    public static void declareBase(Channel channel) throws IOException {
        channel.exchangeDeclare(EXCHANGE, "topic", true);
        channel.exchangeDeclare(DLX, "direct", true);

        // Heartbeats: si nadie los consume en 30 s, ya no sirven.
        Map<String, Object> heartbeatArgs = new HashMap<>();
        heartbeatArgs.put("x-message-ttl", 30000);
        channel.queueDeclare(HEARTBEAT_QUEUE, true, false, false, heartbeatArgs);
        channel.queueBind(HEARTBEAT_QUEUE, EXCHANGE, "heartbeat.*");

        // Auditoría: todo lo que empiece por "audit."
        channel.queueDeclare(AUDIT_QUEUE, true, false, false, null);
        channel.queueBind(AUDIT_QUEUE, EXCHANGE, "audit.#");

        // Cola de fallidos de borrado (recibe lo que rechacen los DataNodes)
        channel.queueDeclare(DELETE_FAILED_QUEUE, true, false, false, null);
        channel.queueBind(DELETE_FAILED_QUEUE, DLX, "block.delete.failed");
    }

    /** La cola de borrado de UN DataNode. Cada DataNode la declara al arrancar. */
    public static void declareDeleteQueue(Channel channel, String datanodeId) throws IOException {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", DLX);                      // si lo rechazan...
        args.put("x-dead-letter-routing-key", "block.delete.failed"); // ...va a la cola de fallidos
        channel.queueDeclare(deleteQueue(datanodeId), true, false, false, args);
        channel.queueBind(deleteQueue(datanodeId), EXCHANGE, deleteKey(datanodeId));
    }
}