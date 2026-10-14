package co.eafit.dfsha.client.blocks;

import co.eafit.dfsha.grpc.v1.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Prueba de integración del DataNode:
 * 1. Se conecta al DataNode en localhost:50061
 * 2. Envía un bloque de 8 MiB por WriteBlock
 * 3. Recibe el checksum
 * 4. Lee el mismo bloque por ReadBlock
 * 5. Compara checksums
 */
public class DataNodeIntegrationTest {

    public static void main(String[] args) throws Exception {
        String endpoint = "localhost:50061";
        String blockId = UUID.randomUUID().toString();
        long blockSize = 8L * 1024 * 1024;

        System.out.println("== Conectando a " + endpoint + " ==");
        ManagedChannel channel = ManagedChannelBuilder.forTarget(endpoint)
                .usePlaintext()
                .build();

        try {
            DataNodeServiceGrpc.DataNodeServiceStub stub = DataNodeServiceGrpc.newStub(channel);

            // -- WriteBlock --
            System.out.println("\n== WriteBlock (" + blockSize + " bytes) ==");
            byte[] payload = new byte[(int) blockSize];
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (i % 251);
            }

            CountDownLatch writeLatch = new CountDownLatch(1);
            final String[] writeChecksum = new String[1];
            final Throwable[] writeError = new Throwable[1];

            StreamObserver<BlockChunk> requestObserver = stub.writeBlock(new StreamObserver<>() {
                @Override public void onNext(BlockWriteResponse r) {
                    writeChecksum[0] = r.getChecksumSha256();
                    System.out.println("  OK: bytes=" + r.getBytesWritten()
                            + " sha256=" + r.getChecksumSha256().substring(0, 16) + "...");
                }
                @Override public void onError(Throwable t) { writeError[0] = t; writeLatch.countDown(); }
                @Override public void onCompleted() { writeLatch.countDown(); }
            });

            requestObserver.onNext(BlockChunk.newBuilder()
                    .setHeader(WriteBlockHeader.newBuilder()
                            .setBlockId(blockId)
                            .setFileId("test-file")
                            .setWriteToken("test-token")
                            .setExpectedSizeBytes(blockSize)
                            .build())
                    .build());

            // Enviar en chunks de 64 KiB
            int chunkSize = 64 * 1024;
            for (int off = 0; off < payload.length; off += chunkSize) {
                int len = Math.min(chunkSize, payload.length - off);
                byte[] slice = new byte[len];
                System.arraycopy(payload, off, slice, 0, len);
                requestObserver.onNext(BlockChunk.newBuilder()
                        .setData(com.google.protobuf.ByteString.copyFrom(slice))
                        .build());
            }
            requestObserver.onCompleted();

            if (!writeLatch.await(30, TimeUnit.SECONDS)) {
                throw new RuntimeException("Timeout en WriteBlock");
            }
            if (writeError[0] != null) throw new RuntimeException(writeError[0]);

            // -- ReadBlock --
            System.out.println("\n== ReadBlock ==");
            CountDownLatch readLatch = new CountDownLatch(1);
            final long[] bytesRead = {0};
            final Throwable[] readError = new Throwable[1];

            stub.readBlock(ReadBlockRequest.newBuilder()
                    .setBlockId(blockId)
                    .setReadToken("test-token")
                    .setOffsetBytes(0)
                    .setLengthBytes(blockSize)
                    .build(), new StreamObserver<>() {
                @Override public void onNext(DataChunk c) { bytesRead[0] += c.getData().size(); }
                @Override public void onError(Throwable t) { readError[0] = t; readLatch.countDown(); }
                @Override public void onCompleted() { readLatch.countDown(); }
            });

            if (!readLatch.await(30, TimeUnit.SECONDS)) {
                throw new RuntimeException("Timeout en ReadBlock");
            }
            if (readError[0] != null) throw new RuntimeException(readError[0]);

            System.out.println("  bytes leídos: " + bytesRead[0]);
            System.out.println("\nOK: WriteBlock + ReadBlock funcionaron.");
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}