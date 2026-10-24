package co.eafit.dfsha.datanode;

import static io.grpc.Status.INTERNAL;
import static io.grpc.Status.INVALID_ARGUMENT;
import static io.grpc.Status.NOT_FOUND;
import static io.grpc.Status.PERMISSION_DENIED;

import co.eafit.dfsha.common.Hash;
import co.eafit.dfsha.common.Rpc;
import co.eafit.dfsha.common.Tokens;
import co.eafit.dfsha.grpc.v1.BlockChunk;
import co.eafit.dfsha.grpc.v1.BlockWriteResponse;
import co.eafit.dfsha.grpc.v1.DataChunk;
import co.eafit.dfsha.grpc.v1.DataNodeServiceGrpc;
import co.eafit.dfsha.grpc.v1.DeleteBlockRequest;
import co.eafit.dfsha.grpc.v1.ReadBlockRequest;
import co.eafit.dfsha.grpc.v1.Status;
import co.eafit.dfsha.grpc.v1.WriteBlockHeader;
import com.google.protobuf.ByteString;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayOutputStream;
import java.nio.file.NoSuchFileException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * El servicio DataNodeService del .proto: recibir, entregar y borrar bloques.
 *
 * <p>Cada operación exige un token firmado por el ControlNode para ESE bloque: un token de otro bloque,
 * vencido o firmado con otra clave se rechaza con PERMISSION_DENIED.
 */
public final class DataNodeServiceImpl extends DataNodeServiceGrpc.DataNodeServiceImplBase {
  private static final Logger log = LoggerFactory.getLogger(DataNodeServiceImpl.class);
  private static final int CHUNK_BYTES = 1024 * 1024;              // los datos viajan en trozos de 1 MiB
  private static final long MAX_BLOCK_BYTES = 64L * 1024 * 1024;

  private final BlockStore store;
  private final Tokens tokens;
  private final ControlLink cluster;

  public DataNodeServiceImpl(BlockStore store, Tokens tokens, ControlLink cluster) {
    this.store = store;
    this.tokens = tokens;
    this.cluster = cluster;
  }

  /**
   * Subida de un bloque (stream del cliente): primero el header con id, token y tamaño; luego los datos.
   * Al final: SHA-256, guardar cifrado, avisar al ControlNode (ReportBlockStored) y responder al cliente.
   */
  @Override
  public StreamObserver<BlockChunk> writeBlock(StreamObserver<BlockWriteResponse> out) {
    return new StreamObserver<>() {
      private WriteBlockHeader header;
      private ByteArrayOutputStream data;
      private boolean finished;

      @Override
      public void onNext(BlockChunk chunk) {
        if (finished) return;
        if (chunk.hasHeader()) {
          header = chunk.getHeader();
          if (!header.getBlockId().equals(tokens.verify(header.getWriteToken(), Tokens.WRITE))) {
            fail(PERMISSION_DENIED, "token de escritura inválido, vencido o de otro bloque");
          } else if (header.getExpectedSizeBytes() <= 0 || header.getExpectedSizeBytes() > MAX_BLOCK_BYTES) {
            fail(INVALID_ARGUMENT, "tamaño de bloque inválido");
          } else {
            data = new ByteArrayOutputStream((int) header.getExpectedSizeBytes());
            cluster.activeTransfers.incrementAndGet();
          }
        } else if (data == null) {
          fail(INVALID_ARGUMENT, "primero debe llegar el header");
        } else if (data.size() + chunk.getData().size() > header.getExpectedSizeBytes()) {
          fail(INVALID_ARGUMENT, "llegaron más bytes de los esperados");
        } else {
          data.writeBytes(chunk.getData().toByteArray());
        }
      }

      @Override
      public void onCompleted() {
        if (finished) return;
        if (data == null || data.size() != header.getExpectedSizeBytes()) {
          fail(INVALID_ARGUMENT, "el tamaño recibido no coincide con expected_size_bytes");
          return;
        }
        try {
          byte[] block = data.toByteArray();
          String sha256 = Hash.sha256(block);
          store.save(header.getBlockId(), block);
          cluster.reportStored(header.getBlockId(), block.length, sha256);   // el ControlNode se entera primero
          done();
          out.onNext(BlockWriteResponse.newBuilder().setStatus(Rpc.ok("bloque guardado"))
              .setBlockId(header.getBlockId()).setBytesWritten(block.length).setChecksumSha256(sha256).build());
          out.onCompleted();
          log.info("Bloque {} guardado ({} bytes)", header.getBlockId(), block.length);
        } catch (StatusRuntimeException e) {
          fail(e.getStatus(), "el ControlNode rechazó el bloque: " + e.getStatus().getDescription());
        } catch (Exception e) {
          fail(INTERNAL, "no pude guardar el bloque: " + e);
        }
      }

      @Override
      public void onError(Throwable t) {
        done();                                  // el cliente canceló: no se guarda nada
      }

      private void fail(io.grpc.Status status, String message) {
        done();
        out.onError(status.withDescription(message).asRuntimeException());
      }

      private void done() {
        if (!finished && data != null) cluster.activeTransfers.decrementAndGet();
        finished = true;
      }
    };
  }

  /** Entrega un bloque (o un pedazo: offset/length, para lecturas parciales) en trozos de 1 MiB. */
  @Override
  public void readBlock(ReadBlockRequest req, StreamObserver<DataChunk> out) {
    if (!req.getBlockId().equals(tokens.verify(req.getReadToken(), Tokens.READ))) {
      out.onError(PERMISSION_DENIED.withDescription("token de lectura inválido, vencido o de otro bloque").asRuntimeException());
      return;
    }
    cluster.activeTransfers.incrementAndGet();
    try {
      byte[] block = store.load(req.getBlockId());
      long start = req.getOffsetBytes();
      long end = req.getLengthBytes() > 0 ? Math.min(block.length, start + req.getLengthBytes()) : block.length;
      if (start < 0 || start > block.length) throw Rpc.error(INVALID_ARGUMENT, "offset fuera del bloque");
      for (long pos = start; pos < end; pos += CHUNK_BYTES) {
        int length = (int) Math.min(CHUNK_BYTES, end - pos);
        out.onNext(DataChunk.newBuilder().setData(ByteString.copyFrom(block, (int) pos, length))
            .setOffsetBytes(pos).setLast(pos + length >= end).build());
      }
      out.onCompleted();
    } catch (StatusRuntimeException e) {
      out.onError(e);
    } catch (NoSuchFileException e) {
      out.onError(NOT_FOUND.withDescription("este DataNode no tiene el bloque").asRuntimeException());
    } catch (Exception e) {      // incluye un bloque alterado en disco (GCM no cuadra)
      out.onError(INTERNAL.withDescription("no pude leer el bloque: " + e).asRuntimeException());
    } finally {
      cluster.activeTransfers.decrementAndGet();
    }
  }

  /** Borrado directo con token DELETE. El borrado normal (rm) llega por RabbitMQ: ver ControlLink. */
  @Override
  public void deleteBlock(DeleteBlockRequest req, StreamObserver<Status> out) {
    Rpc.respond(out, () -> {
      if (!req.getBlockId().equals(tokens.verify(req.getDeleteToken(), Tokens.DELETE))) {
        throw Rpc.error(PERMISSION_DENIED, "token de borrado inválido");
      }
      store.delete(req.getBlockId());
      return Rpc.ok("bloque borrado");
    });
  }
}
