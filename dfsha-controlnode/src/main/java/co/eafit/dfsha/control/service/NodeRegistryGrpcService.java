package co.eafit.dfsha.control.service;

import co.eafit.dfsha.control.model.DataNode;
import co.eafit.dfsha.control.repository.DataNodeRepository;
import co.eafit.dfsha.control.repository.FileFlowRepository;
import co.eafit.dfsha.control.security.TokenDigests;
import co.eafit.dfsha.grpc.v1.NodeRegistryServiceGrpc;
import co.eafit.dfsha.grpc.v1.RegisterDataNodeRequest;
import co.eafit.dfsha.grpc.v1.RegisterDataNodeResponse;
import co.eafit.dfsha.grpc.v1.Status;
import co.eafit.dfsha.grpc.v1.ReportBlockStoredRequest;
import io.grpc.stub.StreamObserver;
import java.sql.SQLException;
import java.util.UUID;

/** Registro persistente. La autenticación del nodo llega con los heartbeats de Fase 2. */
public final class NodeRegistryGrpcService extends NodeRegistryServiceGrpc.NodeRegistryServiceImplBase {
  private final DataNodeRepository nodes;
  private final FileFlowRepository files;
  public NodeRegistryGrpcService(DataNodeRepository nodes) { this(nodes, null); }
  public NodeRegistryGrpcService(DataNodeRepository nodes, FileFlowRepository files) { this.nodes = nodes; this.files = files; }

  @Override public void registerDataNode(RegisterDataNodeRequest request,
                                          StreamObserver<RegisterDataNodeResponse> response) {
    if (request.getNodeName().isBlank() || request.getAdvertisedEndpoint().isBlank()
        || request.getInternalEndpoint().isBlank() || request.getCapacityBytes() < 0) {
      response.onNext(failure("INVALID_ARGUMENT", "nombre, endpoints y capacidad son obligatorios"));
      response.onCompleted(); return;
    }
    try {
      String nodeToken = UUID.randomUUID().toString();
      DataNode node = nodes.register(request.getNodeName(), request.getAdvertisedEndpoint(),
          request.getInternalEndpoint(), request.getCapacityBytes(), TokenDigests.sha256(nodeToken));
      response.onNext(RegisterDataNodeResponse.newBuilder()
          .setStatus(Status.newBuilder().setOk(true).setCode("OK").setMessage("DataNode registrado"))
          .setDatanodeId(node.id().toString())
          .setNodeToken(nodeToken)
          .build());
      response.onCompleted();
    } catch (SQLException e) {
      response.onNext(failure("DATABASE_ERROR", "no se pudo registrar el DataNode"));
      response.onCompleted();
    }
  }

  @Override public void reportBlockStored(ReportBlockStoredRequest request, StreamObserver<Status> response) {
    if(files==null){response.onNext(status(false,"UNAVAILABLE","flujo de archivos no configurado"));response.onCompleted();return;}
    try {
      files.reportStored(UUID.fromString(request.getDatanodeId()),TokenDigests.sha256(request.getNodeToken()),
          UUID.fromString(request.getBlockId()),request.getBytesWritten(),request.getChecksumSha256());
      response.onNext(status(true,"OK","bloque registrado"));response.onCompleted();
    } catch(IllegalArgumentException e){response.onNext(status(false,"INVALID_ARGUMENT","identificadores inválidos"));response.onCompleted();}
    catch(FileFlowException e){response.onNext(status(false,e.kind().name(),e.getMessage()));response.onCompleted();}
  }

  private static RegisterDataNodeResponse failure(String code, String message) {
    return RegisterDataNodeResponse.newBuilder()
        .setStatus(Status.newBuilder().setOk(false).setCode(code).setMessage(message)).build();
  }
  private static Status status(boolean ok,String code,String message){return Status.newBuilder().setOk(ok).setCode(code).setMessage(message).build();}
}
