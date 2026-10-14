package co.eafit.dfsha.client.fake;

import co.eafit.dfsha.grpc.v1.*;
import io.grpc.stub.StreamObserver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementación en memoria de ControlService para poder probar el cliente
 * sin depender del ControlNode real (Camilo). No persiste nada.
 */
public class FakeControlService extends ControlServiceGrpc.ControlServiceImplBase {

    private static final long BLOCK_SIZE = 8L * 1024 * 1024; // 8 MiB
    private static final String FAKE_DATANODE_ID = "dn-fake-1";
    private static final String FAKE_DATANODE_ENDPOINT = "localhost:50061";

    private final Map<String, FileManifest> filesByPath = new ConcurrentHashMap<>();

    @Override
    public void login(LoginRequest req, StreamObserver<LoginResponse> obs) {
        LoginResponse resp = LoginResponse.newBuilder()
                .setAccessToken("fake-token-" + UUID.randomUUID())
                .setExpiresAtEpochSeconds(System.currentTimeMillis() / 1000 + 1800)
                .setUserId("user-fake")
                .build();
        obs.onNext(resp);
        obs.onCompleted();
    }

    @Override
    public void allocateFile(AllocateFileRequest req, StreamObserver<AllocateFileResponse> obs) {
        long fileSize = req.getFileSizeBytes();
        String path = req.getPath();
        String fileId = UUID.randomUUID().toString();

        int numBlocks = (int) Math.ceil((double) fileSize / BLOCK_SIZE);
        List<BlockPlan> plans = new ArrayList<>();
        long offset = 0;
        for (int i = 0; i < numBlocks; i++) {
            long size = Math.min(BLOCK_SIZE, fileSize - offset);
            plans.add(BlockPlan.newBuilder()
                    .setBlockId(UUID.randomUUID().toString())
                    .setOffsetBytes(offset)
                    .setSizeBytes(size)
                    .setDatanodeId(FAKE_DATANODE_ID)
                    .setDatanodeEndpoint(FAKE_DATANODE_ENDPOINT)
                    .setWriteToken("fake-write-token-" + i)
                    .build());
            offset += size;
        }

        AllocateFileResponse resp = AllocateFileResponse.newBuilder()
                .setFile(FileRef.newBuilder().setFileId(fileId).setPath(path).build())
                .setBlockSizeBytes(BLOCK_SIZE)
                .addAllBlocks(plans)
                .build();

        // Guardamos el plan para que GetFileManifest lo devuelva
        filesByPath.put(path, FileManifest.newBuilder()
                .setFile(FileRef.newBuilder().setFileId(fileId).setPath(path).build())
                .setFileSizeBytes(fileSize)
                .addAllBlocks(plans)
                .build());

        obs.onNext(resp);
        obs.onCompleted();
    }

    @Override
    public void commitFile(CommitFileRequest req, StreamObserver<CommitFileResponse> obs) {
        CommitFileResponse resp = CommitFileResponse.newBuilder()
                .setStatus(Status.newBuilder().setOk(true).setCode("OK").setMessage("fake commit").build())
                .build();
        obs.onNext(resp);
        obs.onCompleted();
    }

    @Override
    public void getFileManifest(FileRef req, StreamObserver<FileManifest> obs) {
        FileManifest m = filesByPath.get(req.getPath());
        if (m == null) {
            obs.onError(io.grpc.Status.NOT_FOUND.withDescription("no existe " + req.getPath()).asRuntimeException());
            return;
        }
        obs.onNext(m);
        obs.onCompleted();
    }

    @Override
    public void listDirectory(PathRequest req, StreamObserver<ListResponse> obs) {
        List<DirectoryEntry> entries = new ArrayList<>();
        for (String path : filesByPath.keySet()) {
            entries.add(DirectoryEntry.newBuilder()
                    .setPath(path)
                    .setDirectory(false)
                    .setSizeBytes(filesByPath.get(path).getFileSizeBytes())
                    .build());
        }
        obs.onNext(ListResponse.newBuilder().addAllEntries(entries).build());
        obs.onCompleted();
    }

    @Override
    public void createDirectory(PathRequest req, StreamObserver<Status> obs) {
        obs.onNext(Status.newBuilder().setOk(true).setCode("OK").build());
        obs.onCompleted();
    }

    @Override
    public void deletePath(PathRequest req, StreamObserver<Status> obs) {
        filesByPath.remove(req.getPath());
        obs.onNext(Status.newBuilder().setOk(true).setCode("OK").build());
        obs.onCompleted();
    }
}