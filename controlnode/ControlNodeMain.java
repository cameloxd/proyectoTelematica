package co.eafit.dfsha.control;

import co.eafit.dfsha.grpc.v1.*;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Hito 2 bootstrap. Persistence adapters are intentionally isolated for the next step. */
public final class ControlNodeMain {
  static final long BLOCK_SIZE = Long.parseLong(System.getenv().getOrDefault("DFS_BLOCK_SIZE_BYTES", "8388608"));
  static final Map<String, Node> nodes = new ConcurrentHashMap<>();
  static final Map<String, FileRecord> files = new ConcurrentHashMap<>();
  static final Map<String, FileRecord> operations = new ConcurrentHashMap<>();

  public static void main(String[] args) throws Exception {
    int port = Integer.parseInt(System.getenv().getOrDefault("GRPC_PORT", "50051"));
    Server server = ServerBuilder.forPort(port)
        .addService(new ControlServiceImpl())
        .addService(new RegistryImpl())
        .build().start();
    System.out.println("DFSha ControlNode listening on " + port + " (in-memory Hito 2 bootstrap)");
    Runtime.getRuntime().addShutdownHook(new Thread(() -> server.shutdown()));
    server.awaitTermination();
  }

  static final class Node {
    final String id, name, endpoint, internal, token;
    final long capacity;
    volatile long free;
    volatile int active;
    volatile long blocks;
    Node(String id, String name, String endpoint, String internal, String token,
         long capacity, long free, int active, long blocks) {
      this.id=id; this.name=name; this.endpoint=endpoint; this.internal=internal;
      this.token=token; this.capacity=capacity; this.free=free; this.active=active; this.blocks=blocks;
    }
    String id(){return id;} String name(){return name;} String endpoint(){return endpoint;}
    String internal(){return internal;} String token(){return token;}
  }
  static final class FileRecord {
    final String id = UUID.randomUUID().toString(); final String path; final long size; final String op;
    final List<BlockPlan> plans; final Set<String> stored = ConcurrentHashMap.newKeySet(); volatile boolean committed;
    FileRecord(String path, long size, String op, List<BlockPlan> plans) { this.path=path; this.size=size; this.op=op; this.plans=plans; }
  }

  static final class ControlServiceImpl extends ControlServiceGrpc.ControlServiceImplBase {
    @Override public void login(LoginRequest r, StreamObserver<LoginResponse> o) {
      if (r.getUsername().isBlank() || r.getPassword().isBlank()) { o.onError(io.grpc.Status.UNAUTHENTICATED.asRuntimeException()); return; }
      long exp = Instant.now().plusSeconds(1800).getEpochSecond();
      o.onNext(LoginResponse.newBuilder().setAccessToken("bootstrap-" + UUID.randomUUID()).setUserId(r.getUsername()).setExpiresAtEpochSeconds(exp).build()); o.onCompleted();
    }
    @Override public void allocateFile(AllocateFileRequest r, StreamObserver<AllocateFileResponse> o) {
      String key = r.getOperationId(); FileRecord existing = operations.get(key);
      if (existing != null) { o.onNext(response(existing)); o.onCompleted(); return; }
      if (r.getPath().isBlank() || r.getFileSizeBytes() < 0) { o.onError(io.grpc.Status.INVALID_ARGUMENT.asRuntimeException()); return; }
      List<Node> eligible = new ArrayList<>(nodes.values()); eligible.sort(Comparator.comparing(Node::name));
      if (r.getFileSizeBytes() > 0 && eligible.isEmpty()) { o.onError(io.grpc.Status.RESOURCE_EXHAUSTED.withDescription("No DataNodes registered").asRuntimeException()); return; }
      int count = (int)Math.max(1, (r.getFileSizeBytes() + BLOCK_SIZE - 1) / BLOCK_SIZE); List<BlockPlan> plans = new ArrayList<>();
      Map<String,Integer> used = new HashMap<>();
      for (int i=0; i<count && r.getFileSizeBytes()>0; i++) {
        Node n = eligible.stream().min(Comparator.comparingInt(x -> used.getOrDefault(x.id(), 0))).orElseThrow(); used.merge(n.id(),1,Integer::sum);
        long offset=i*BLOCK_SIZE, size=Math.min(BLOCK_SIZE, r.getFileSizeBytes()-offset); String bid=UUID.randomUUID().toString(); long exp=Instant.now().plusSeconds(900).getEpochSecond();
        plans.add(BlockPlan.newBuilder().setBlockId(bid).setBlockIndex(i).setOffsetBytes(offset).setSizeBytes(size).setDatanodeId(n.id()).setDatanodeEndpoint(n.endpoint()).setWriteToken("write-"+UUID.randomUUID()).setReadToken("read-"+UUID.randomUUID()).setTokenExpiresAtEpochSeconds(exp).build());
      }
      FileRecord fr=new FileRecord(r.getPath(),r.getFileSizeBytes(),key,plans); operations.put(key,fr); files.put(fr.id,fr); o.onNext(response(fr)); o.onCompleted();
    }
    private AllocateFileResponse response(FileRecord f) { return AllocateFileResponse.newBuilder().setFile(FileRef.newBuilder().setFileId(f.id).setPath(f.path)).setBlockSizeBytes(BLOCK_SIZE).addAllBlocks(f.plans).build(); }
    @Override public void commitFile(CommitFileRequest r, StreamObserver<CommitFileResponse> o) { FileRecord f=files.get(r.getFileId()); if(f==null){o.onError(io.grpc.Status.NOT_FOUND.asRuntimeException());return;} List<String> missing=f.plans.stream().map(BlockPlan::getBlockId).filter(x->!f.stored.contains(x)).toList(); if(missing.isEmpty())f.committed=true; o.onNext(CommitFileResponse.newBuilder().setStatus(Status.newBuilder().setOk(missing.isEmpty()).setCode(missing.isEmpty()?"OK":"MISSING_BLOCKS")).addAllMissingBlockIds(missing).build()); o.onCompleted(); }
    @Override public void getFileManifest(FileRef r, StreamObserver<FileManifest> o) { FileRecord f=files.get(r.getFileId()); if(f==null){o.onError(io.grpc.Status.NOT_FOUND.asRuntimeException());return;} o.onNext(FileManifest.newBuilder().setFile(r).setFileSizeBytes(f.size).addAllBlocks(f.plans).build());o.onCompleted(); }
    @Override public void listDirectory(PathRequest r, StreamObserver<ListResponse> o){ o.onNext(ListResponse.newBuilder().build());o.onCompleted(); }
    @Override public void createDirectory(PathRequest r, StreamObserver<Status> o){ o.onNext(Status.newBuilder().setOk(true).setCode("OK").build());o.onCompleted(); }
    @Override public void deletePath(PathRequest r, StreamObserver<Status> o){ o.onNext(Status.newBuilder().setOk(true).setCode("OK").build());o.onCompleted(); }
  }

  static final class RegistryImpl extends NodeRegistryServiceGrpc.NodeRegistryServiceImplBase {
    @Override public void registerDataNode(RegisterDataNodeRequest r, StreamObserver<RegisterDataNodeResponse> o){ Node old=nodes.values().stream().filter(n->n.name().equals(r.getNodeName())).findFirst().orElse(null); String id=old==null?UUID.randomUUID().toString():old.id(); String tok=old==null?UUID.randomUUID().toString():old.token(); nodes.put(id,new Node(id,r.getNodeName(),r.getAdvertisedEndpoint(),r.getInternalEndpoint(),tok,r.getCapacityBytes(),r.getCapacityBytes(),0,0)); o.onNext(RegisterDataNodeResponse.newBuilder().setStatus(Status.newBuilder().setOk(true).setCode("OK")).setDatanodeId(id).setNodeToken(tok).build());o.onCompleted(); }
    @Override public void publishHeartbeat(Heartbeat r, StreamObserver<Status> o){ Node n=nodes.get(r.getDatanodeId()); if(n==null||!n.token().equals(r.getNodeToken())){o.onError(io.grpc.Status.UNAUTHENTICATED.asRuntimeException());return;} n.free=r.getFreeBytes();n.active=r.getActiveTransfers();n.blocks=r.getStoredBlocks();o.onNext(Status.newBuilder().setOk(true).setCode("OK").build());o.onCompleted(); }
    @Override public void reportBlockStored(ReportBlockStoredRequest r, StreamObserver<Status> o){ Node n=nodes.get(r.getDatanodeId()); if(n==null||!n.token().equals(r.getNodeToken())){o.onError(io.grpc.Status.UNAUTHENTICATED.asRuntimeException());return;} files.values().stream().filter(f->f.plans.stream().anyMatch(p->p.getBlockId().equals(r.getBlockId()))).findFirst().ifPresent(f->f.stored.add(r.getBlockId())); o.onNext(Status.newBuilder().setOk(true).setCode("OK").build());o.onCompleted(); }
  }
}
