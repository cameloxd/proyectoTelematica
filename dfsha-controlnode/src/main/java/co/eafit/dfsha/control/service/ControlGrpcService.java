package co.eafit.dfsha.control.service;

import co.eafit.dfsha.common.Ctx;
import co.eafit.dfsha.control.model.BlockPlacement;
import co.eafit.dfsha.control.model.CommitResult;
import co.eafit.dfsha.control.model.FilePlan;
import co.eafit.dfsha.control.repository.FileFlowRepository;
import co.eafit.dfsha.control.repository.JdbcUserRepository;
import co.eafit.dfsha.control.security.BlockTokenSigner;
import co.eafit.dfsha.control.security.BlockTokenSigner.Action;
import co.eafit.dfsha.grpc.v1.*;
import io.grpc.stub.StreamObserver;
import java.sql.SQLException;
import java.util.UUID;

public final class ControlGrpcService extends ControlServiceGrpc.ControlServiceImplBase {
  private final FileFlowRepository files;
  private final JdbcUserRepository users;
  private final BlockTokenSigner tokens;
  private final int blockSizeBytes;
  private final long pendingTtlSeconds;

  public ControlGrpcService(FileFlowRepository files, JdbcUserRepository users,
                            BlockTokenSigner tokens, int blockSizeBytes, long pendingTtlSeconds) {
    this.files=files;this.users=users;this.tokens=tokens;this.blockSizeBytes=blockSizeBytes;
    this.pendingTtlSeconds=pendingTtlSeconds;
  }

  @Override public void allocateFile(AllocateFileRequest request, StreamObserver<AllocateFileResponse> out) {
    try {
      UUID owner=ownerId();
      FilePlan plan=files.allocate(owner,request.getPath(),request.getFileSizeBytes(),
          request.getOperationId(),blockSizeBytes,pendingTtlSeconds);
      AllocateFileResponse.Builder response=AllocateFileResponse.newBuilder()
          .setFile(fileRef(plan)).setBlockSizeBytes(plan.blockSizeBytes());
      for(BlockPlacement block:plan.blocks())response.addBlocks(blockPlan(plan,block,owner,true));
      out.onNext(response.build());out.onCompleted();
    } catch(FileFlowException e){out.onError(toGrpc(e));}
  }

  @Override public void commitFile(CommitFileRequest request, StreamObserver<CommitFileResponse> out) {
    try {
      UUID fileId=parseUuid(request.getFileId(),"file_id");
      CommitResult result=files.commit(ownerId(),fileId,request.getOperationId());
      CommitFileResponse.Builder response=CommitFileResponse.newBuilder()
          .setStatus(co.eafit.dfsha.grpc.v1.Status.newBuilder().setOk(result.committed())
              .setCode(result.committed()?"OK":"MISSING_BLOCKS")
              .setMessage(result.committed()?"archivo confirmado":"faltan bloques por almacenar"));
      result.missingBlockIds().forEach(id->response.addMissingBlockIds(id.toString()));
      out.onNext(response.build());out.onCompleted();
    } catch(FileFlowException e){out.onError(toGrpc(e));}
  }

  @Override public void getFileManifest(FileRef request, StreamObserver<FileManifest> out) {
    try {
      UUID owner=ownerId();FilePlan plan=files.manifest(owner,request.getFileId(),request.getPath());
      FileManifest.Builder manifest=FileManifest.newBuilder().setFile(fileRef(plan)).setFileSizeBytes(plan.sizeBytes());
      for(BlockPlacement block:plan.blocks())manifest.addBlocks(blockPlan(plan,block,owner,false));
      out.onNext(manifest.build());out.onCompleted();
    } catch(FileFlowException e){out.onError(toGrpc(e));}
  }

  private UUID ownerId() throws FileFlowException {
    String principal=Ctx.USER_ID.get();
    if(principal==null||principal.isBlank())throw new FileFlowException(FileFlowException.Kind.UNAUTHENTICATED,"falta identidad de usuario");
    try {
      try {
        UUID id=UUID.fromString(principal);
        return users.findActiveById(id).orElseThrow(()->new FileFlowException(FileFlowException.Kind.UNAUTHENTICATED,"usuario inactivo o inexistente")).id();
      } catch(IllegalArgumentException ignored) {
        return users.findByUsername(principal).filter(JdbcUserRepository.User::active)
            .orElseThrow(()->new FileFlowException(FileFlowException.Kind.UNAUTHENTICATED,"usuario inactivo o inexistente")).id();
      }
    } catch(SQLException e){throw new FileFlowException(FileFlowException.Kind.INTERNAL,"no se pudo resolver el usuario",e);}
  }

  private BlockPlan blockPlan(FilePlan file,BlockPlacement block,UUID owner,boolean includeWrite) {
    BlockTokenSigner.SignedToken read=tokens.issue(Action.READ,block.blockId(),file.fileId(),owner);
    BlockPlan.Builder plan=BlockPlan.newBuilder().setBlockId(block.blockId().toString())
        .setBlockIndex(block.blockIndex()).setOffsetBytes(block.offsetBytes()).setSizeBytes(block.sizeBytes())
        .setDatanodeId(block.dataNodeId().toString()).setDatanodeEndpoint(block.dataNodeEndpoint())
        .setReadToken(read.value()).setTokenExpiresAtEpochSeconds(read.expiresAtEpochSeconds());
    if(block.checksumSha256()!=null)plan.setChecksumSha256(block.checksumSha256());
    if(includeWrite)plan.setWriteToken(tokens.issue(Action.WRITE,block.blockId(),file.fileId(),owner).value());
    return plan.build();
  }

  private static FileRef fileRef(FilePlan plan){return FileRef.newBuilder().setFileId(plan.fileId().toString()).setPath(plan.path()).build();}
  private static UUID parseUuid(String value,String name)throws FileFlowException{try{return UUID.fromString(value);}catch(Exception e){throw new FileFlowException(FileFlowException.Kind.INVALID_ARGUMENT,name+" inválido");}}
  private static io.grpc.StatusRuntimeException toGrpc(FileFlowException e){
    io.grpc.Status status=switch(e.kind()){
      case INVALID_ARGUMENT->io.grpc.Status.INVALID_ARGUMENT;case NOT_FOUND->io.grpc.Status.NOT_FOUND;
      case ALREADY_EXISTS->io.grpc.Status.ALREADY_EXISTS;case RESOURCE_EXHAUSTED->io.grpc.Status.RESOURCE_EXHAUSTED;
      case FAILED_PRECONDITION->io.grpc.Status.FAILED_PRECONDITION;case UNAUTHENTICATED->io.grpc.Status.UNAUTHENTICATED;
      case INTERNAL->io.grpc.Status.INTERNAL;};return status.withDescription(e.getMessage()).asRuntimeException();
  }
}
