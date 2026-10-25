package co.eafit.dfsha.datanode;

import co.eafit.dfsha.common.Hash;
import co.eafit.dfsha.common.Rpc;
import co.eafit.dfsha.grpc.v1.DataNodeServiceGrpc;
import co.eafit.dfsha.grpc.v1.ReadBlockRequest;
import co.eafit.dfsha.grpc.v1.ReplicationTask;
import io.grpc.ManagedChannel;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Copia de réplicas entre DataNodes.
 *
 * <p>El ControlNode publica en RabbitMQ "trae el bloque X desde el nodo Y". Este DataNode (el destino)
 * JALA el bloque con ReadBlock, el mismo rpc que usa el cliente, verifica el SHA-256, lo guarda cifrado
 * y avisa al ControlNode. Si algo falla lanza una excepción: el mensaje va a la cola de fallidos y el
 * ControlNode lo vuelve a pedir en un escaneo posterior.
 */
public final class Replicator {
  private static final Logger log = LoggerFactory.getLogger(Replicator.class);

  private final BlockStore store;
  private final ControlLink cluster;

  public Replicator(BlockStore store, ControlLink cluster) {
    this.store = store;
    this.cluster = cluster;
  }

  public void copy(ReplicationTask task) throws Exception {
    ManagedChannel source = Rpc.channel(task.getSourceEndpoint());
    try {
      ByteArrayOutputStream data = new ByteArrayOutputStream((int) task.getSizeBytes());
      DataNodeServiceGrpc.newBlockingStub(source).withDeadlineAfter(60, TimeUnit.SECONDS)
          .readBlock(ReadBlockRequest.newBuilder().setBlockId(task.getBlockId()).setReadToken(task.getReadToken()).build())
          .forEachRemaining(chunk -> data.writeBytes(chunk.getData().toByteArray()));
      byte[] block = data.toByteArray();
      String sha256 = Hash.sha256(block);
      if (block.length != task.getSizeBytes() || !sha256.equals(task.getChecksumSha256())) {
        throw new IllegalStateException("la copia del bloque " + task.getBlockId() + " llegó dañada");
      }
      store.save(task.getBlockId(), block);
      cluster.reportStored(task.getBlockId(), block.length, sha256);
      log.info("Réplica del bloque {} copiada desde {}", task.getBlockId(), task.getSourceEndpoint());
    } finally {
      source.shutdownNow();
    }
  }
}
