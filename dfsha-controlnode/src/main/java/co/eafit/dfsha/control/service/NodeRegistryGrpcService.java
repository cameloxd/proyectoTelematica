package co.eafit.dfsha.control.service;

import co.eafit.dfsha.control.model.DataNode;
import co.eafit.dfsha.control.repository.DataNodeRepository;
import co.eafit.dfsha.grpc.v1.NodeRegistryServiceGrpc;
import co.eafit.dfsha.grpc.v1.RegisterDataNodeRequest;
import co.eafit.dfsha.grpc.v1.RegisterDataNodeResponse;
import co.eafit.dfsha.grpc.v1.Status;
import io.grpc.stub.StreamObserver;
import java.sql.SQLException;

/** Registro persistente. La autenticación del nodo llega con los heartbeats de Fase 2. */
public final class NodeRegistryGrpcService extends NodeRegistryServiceGrpc.NodeRegistryServiceImplBase {
  private final DataNodeRepository nodes;
  public NodeRegistryGrpcService(DataNodeRepository nodes) { this.nodes = nodes; }

  @Override public void registerDataNode(RegisterDataNodeRequest request,
                                          StreamObserver<RegisterDataNodeResponse> response) {
    if (request.getNodeName().isBlank() || request.getAdvertisedEndpoint().isBlank()
        || request.getInternalEndpoint().isBlank() || request.getCapacityBytes() < 0) {
      response.onNext(failure("INVALID_ARGUMENT", "nombre, endpoints y capacidad son obligatorios"));
      response.onCompleted(); return;
    }
    try {
      DataNode node = nodes.register(request.getNodeName(), request.getAdvertisedEndpoint(),
          request.getInternalEndpoint(), request.getCapacityBytes());
      response.onNext(RegisterDataNodeResponse.newBuilder()
          .setStatus(Status.newBuilder().setOk(true).setCode("OK").setMessage("DataNode registrado"))
          .setDatanodeId(node.id().toString())
          .build());
      response.onCompleted();
    } catch (SQLException e) {
      response.onNext(failure("DATABASE_ERROR", "no se pudo registrar el DataNode"));
      response.onCompleted();
    }
  }

  private static RegisterDataNodeResponse failure(String code, String message) {
    return RegisterDataNodeResponse.newBuilder()
        .setStatus(Status.newBuilder().setOk(false).setCode(code).setMessage(message)).build();
  }
}
