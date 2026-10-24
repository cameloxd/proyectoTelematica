package co.eafit.dfsha.datanode;

import co.eafit.dfsha.grpc.v1.*;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Almacenamiento local de bloques en disco.
 * Escribe primero a un archivo temporal y renombra atómicamente al terminar.
 * Calcula SHA-256 a medida que llegan los fragmentos.
 * Sin validación de tokens (se agrega en M2.4).
 */
public class BlockStorageService extends DataNodeServiceGrpc.DataNodeServiceImplBase {

    private final Path storageRoot;

    public BlockStorageService(Path storageRoot) throws IOException {
        this.storageRoot = storageRoot;
        Files.createDirectories(storageRoot);
    }

    @Override
    public StreamObserver<BlockChunk> writeBlock(StreamObserver<BlockWriteResponse> obs) {
        return new StreamObserver<>() {
            private WriteBlockHeader header;
            private Path tempFile;
            private FileChannel channel;
            private MessageDigest digest;
            private long received;
            private boolean failed;

            @Override
            public void onNext(BlockChunk chunk) {
                if (failed) return;
                try {
                    if (chunk.hasHeader()) {
                        if (header != null) {
                            throw new IllegalStateException("Header duplicado");
                        }
                        header = chunk.getHeader();
                        tempFile = storageRoot.resolve(header.getBlockId() + ".tmp");
                        channel = FileChannel.open(tempFile,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE,
                                StandardOpenOption.TRUNCATE_EXISTING);
                        digest = MessageDigest.getInstance("SHA-256");
                    } else if (chunk.hasData()) {
                        if (header == null) {
                            throw new IllegalStateException("Datos antes del header");
                        }
                        ByteBuffer buf = chunk.getData().asReadOnlyByteBuffer();
                        digest.update(buf.duplicate());
                        while (buf.hasRemaining()) {
                            channel.write(buf);
                        }
                        received += chunk.getData().size();
                    }
                } catch (Exception e) {
                    failed = true;
                    cleanup();
                    obs.onError(io.grpc.Status.INTERNAL
                            .withDescription("Error en WriteBlock: " + e.getMessage())
                            .asRuntimeException());
                }
            }

            @Override
            public void onError(Throwable t) {
                failed = true;
                cleanup();
            }

            @Override
            public void onCompleted() {
                if (failed) return;
                try {
                    if (header == null) {
                        throw new IllegalStateException("No llegó header");
                    }
                    if (received != header.getExpectedSizeBytes()) {
                        cleanup();
                        obs.onError(io.grpc.Status.INVALID_ARGUMENT
                                .withDescription("Tamaño no coincide: esperado "
                                        + header.getExpectedSizeBytes() + " recibido " + received)
                                .asRuntimeException());
                        return;
                    }
                    channel.force(true);
                    channel.close();

                    Path finalFile = storageRoot.resolve(header.getBlockId() + ".blk");
                    Files.move(tempFile, finalFile,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);

                    String checksum = HexFormat.of().formatHex(digest.digest());

                    obs.onNext(BlockWriteResponse.newBuilder()
                            .setStatus(Status.newBuilder().setOk(true).setCode("OK").build())
                            .setBlockId(header.getBlockId())
                            .setBytesWritten(received)
                            .setChecksumSha256(checksum)
                            .build());
                    obs.onCompleted();
                } catch (Exception e) {
                    failed = true;
                    cleanup();
                    obs.onError(io.grpc.Status.INTERNAL
                            .withDescription("Error al cerrar bloque: " + e.getMessage())
                            .asRuntimeException());
                }
            }

            private void cleanup() {
                try { if (channel != null) channel.close(); } catch (IOException ignored) {}
                try { if (tempFile != null) Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
            }
        };
    }

    @Override
    public void readBlock(ReadBlockRequest req, StreamObserver<DataChunk> obs) {
        Path file = storageRoot.resolve(req.getBlockId() + ".blk");
        if (!Files.exists(file)) {
            obs.onError(io.grpc.Status.NOT_FOUND
                    .withDescription("Bloque no existe: " + req.getBlockId())
                    .asRuntimeException());
            return;
        }
        try (InputStream in = Files.newInputStream(file)) {
            long offset = req.getOffsetBytes();
            long remaining = req.getLengthBytes() > 0
                    ? req.getLengthBytes()
                    : Files.size(file) - offset;

            // skip hasta offset
            long skipped = 0;
            while (skipped < offset) {
                long s = in.skip(offset - skipped);
                if (s <= 0) throw new IOException("No se pudo saltar a offset " + offset);
                skipped += s;
            }

            byte[] buffer = new byte[64 * 1024];
            long pos = offset;
            while (remaining > 0) {
                int toRead = (int) Math.min(buffer.length, remaining);
                int read = in.read(buffer, 0, toRead);
                if (read == -1) break;
                remaining -= read;
                boolean last = remaining == 0;
                obs.onNext(DataChunk.newBuilder()
                        .setData(com.google.protobuf.ByteString.copyFrom(buffer, 0, read))
                        .setOffsetBytes(pos)
                        .setLast(last)
                        .build());
                pos += read;
            }
            obs.onCompleted();
        } catch (IOException e) {
            obs.onError(io.grpc.Status.INTERNAL
                    .withDescription("Error en ReadBlock: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public void deleteBlock(DeleteBlockRequest req, StreamObserver<Status> obs) {
        Path file = storageRoot.resolve(req.getBlockId() + ".blk");
        try {
            Files.deleteIfExists(file); // idempotente
            obs.onNext(Status.newBuilder().setOk(true).setCode("OK").build());
            obs.onCompleted();
        } catch (IOException e) {
            obs.onError(io.grpc.Status.INTERNAL
                    .withDescription("Error en DeleteBlock: " + e.getMessage())
                    .asRuntimeException());
        }
    }
}