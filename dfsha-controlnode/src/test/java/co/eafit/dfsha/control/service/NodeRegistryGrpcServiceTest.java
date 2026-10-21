package co.eafit.dfsha.control.service;

import static org.junit.jupiter.api.Assertions.*;

import co.eafit.dfsha.control.model.DataNode;
import co.eafit.dfsha.control.repository.DataNodeRepository;
import co.eafit.dfsha.control.security.TokenDigests;
import co.eafit.dfsha.grpc.v1.RegisterDataNodeRequest;
import co.eafit.dfsha.grpc.v1.RegisterDataNodeResponse;
import io.grpc.stub.StreamObserver;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NodeRegistryGrpcServiceTest {
  @Test void persistsAValidRegistration() {
    UUID id = UUID.randomUUID();
    String[] persistedTokenHash = new String[1];
    DataNodeRepository repository = new DataNodeRepository() {
      @Override public DataNode register(String name, String endpoint, String internal, long capacity, String tokenHash) {
        persistedTokenHash[0] = tokenHash;
        return new DataNode(id, name, endpoint, internal, "ALIVE", capacity, capacity, 0, 0, 0);
      }
      @Override public Optional<DataNode> findByName(String name) { return Optional.empty(); }
    };
    CapturingObserver observer = new CapturingObserver();
    new NodeRegistryGrpcService(repository).registerDataNode(RegisterDataNodeRequest.newBuilder()
        .setNodeName("node-1").setAdvertisedEndpoint("localhost:50061")
        .setInternalEndpoint("node-1:50061").setCapacityBytes(1024).build(), observer);
    assertTrue(observer.response.getStatus().getOk());
    assertEquals(id.toString(), observer.response.getDatanodeId());
    assertFalse(observer.response.getNodeToken().isBlank());
    assertEquals(TokenDigests.sha256(observer.response.getNodeToken()), persistedTokenHash[0]);
    assertTrue(observer.completed);
  }

  @Test void rejectsIncompleteRegistration() {
    DataNodeRepository unused = new DataNodeRepository() {
      @Override public DataNode register(String a, String b, String c, long d, String tokenHash) throws SQLException { throw new AssertionError(); }
      @Override public Optional<DataNode> findByName(String name) { return Optional.empty(); }
    };
    CapturingObserver observer = new CapturingObserver();
    new NodeRegistryGrpcService(unused).registerDataNode(RegisterDataNodeRequest.getDefaultInstance(), observer);
    assertFalse(observer.response.getStatus().getOk());
    assertEquals("INVALID_ARGUMENT", observer.response.getStatus().getCode());
  }

  private static final class CapturingObserver implements StreamObserver<RegisterDataNodeResponse> {
    RegisterDataNodeResponse response; boolean completed;
    @Override public void onNext(RegisterDataNodeResponse value) { response = value; }
    @Override public void onError(Throwable throwable) { fail(throwable); }
    @Override public void onCompleted() { completed = true; }
  }
}
