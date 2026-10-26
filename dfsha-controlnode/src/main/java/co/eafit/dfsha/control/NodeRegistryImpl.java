package co.eafit.dfsha.control;

import static io.grpc.Status.FAILED_PRECONDITION;
import static io.grpc.Status.INVALID_ARGUMENT;
import static io.grpc.Status.NOT_FOUND;
import static io.grpc.Status.PERMISSION_DENIED;
import static io.grpc.Status.UNAUTHENTICATED;

import co.eafit.dfsha.common.Hash;
import co.eafit.dfsha.common.Rpc;
import co.eafit.dfsha.grpc.v1.NodeRegistryServiceGrpc;
import co.eafit.dfsha.grpc.v1.RegisterDataNodeRequest;
import co.eafit.dfsha.grpc.v1.RegisterDataNodeResponse;
import co.eafit.dfsha.grpc.v1.ReportBlockStoredRequest;
import co.eafit.dfsha.grpc.v1.Status;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lo que los DataNodes le dicen al ControlNode (servicio NodeRegistryService del .proto).
 *
 * <p>Seguridad entre nodos: para registrarse hay que conocer la clave del clúster (REGISTRATION_KEY).
 * El registro devuelve un node_token aleatorio; en la base solo se guarda su SHA-256 y el DataNode lo
 * manda en cada ReportBlockStored y en cada heartbeat.
 */
public final class NodeRegistryImpl extends NodeRegistryServiceGrpc.NodeRegistryServiceImplBase {
  private static final Logger log = LoggerFactory.getLogger(NodeRegistryImpl.class);

  private final Db db;
  private final byte[] registrationKey;

  public NodeRegistryImpl(Db db, String registrationKey) {
    this.db = db;
    this.registrationKey = registrationKey.getBytes(StandardCharsets.UTF_8);
  }

  /** Alta (o re-alta tras reiniciar) de un DataNode. Por node_name: el mismo nodo conserva su id. */
  @Override
  public void registerDataNode(RegisterDataNodeRequest req, StreamObserver<RegisterDataNodeResponse> out) {
    Rpc.respond(out, () -> {
      if (!MessageDigest.isEqual(registrationKey, req.getRegistrationKey().getBytes(StandardCharsets.UTF_8))) {
        throw Rpc.error(PERMISSION_DENIED, "clave de registro del clúster incorrecta");
      }
      if (req.getNodeName().isBlank() || req.getAdvertisedEndpoint().isBlank()
          || req.getInternalEndpoint().isBlank() || req.getCapacityBytes() <= 0) {
        throw Rpc.error(INVALID_ARGUMENT, "nombre, endpoints y capacidad son obligatorios");
      }
      String nodeToken = UUID.randomUUID().toString();
      String nodeId = db.tx(c -> Db.one(c, """
          INSERT INTO datanode(node_name, endpoint, internal_endpoint, node_token_hash, capacity_bytes, free_bytes)
          VALUES (?, ?, ?, ?, ?, ?)
          ON CONFLICT (node_name) DO UPDATE SET
            endpoint = EXCLUDED.endpoint, internal_endpoint = EXCLUDED.internal_endpoint,
            node_token_hash = EXCLUDED.node_token_hash, capacity_bytes = EXCLUDED.capacity_bytes,
            status = 'ALIVE', last_heartbeat_at = now()
          RETURNING datanode_id""",
          r -> r.getString(1),
          req.getNodeName(), req.getAdvertisedEndpoint(), req.getInternalEndpoint(),
          Hash.sha256(nodeToken), req.getCapacityBytes(), req.getCapacityBytes()));
      log.info("DataNode {} registrado: id={} endpoint={}", req.getNodeName(), nodeId, req.getAdvertisedEndpoint());
      return RegisterDataNodeResponse.newBuilder()
          .setStatus(Rpc.ok("DataNode registrado")).setDatanodeId(nodeId).setNodeToken(nodeToken).build();
    });
  }

  /**
   * "Ya tengo este bloque". Lo llama el DataNode al terminar de guardar una copia (primaria o réplica):
   * la ubicación pasa a WRITTEN. La primera copia fija el checksum; las réplicas deben coincidir con él.
   */
  @Override
  public void reportBlockStored(ReportBlockStoredRequest req, StreamObserver<Status> out) {
    Rpc.respond(out, () -> {
      UUID nodeId = UUID.fromString(req.getDatanodeId());
      UUID blockId = UUID.fromString(req.getBlockId());
      String checksum = req.getChecksumSha256();
      if (!checksum.matches("[0-9a-f]{64}")) throw Rpc.error(INVALID_ARGUMENT, "SHA-256 inválido");
      db.tx(c -> {
        requireNodeToken(c, nodeId, req.getNodeToken());
        Block block = Db.one(c, "SELECT size_bytes, checksum_sha256 FROM dfs_block WHERE block_id = ? FOR UPDATE",
            r -> new Block(r.getLong(1), r.getString(2)), blockId);
        if (block == null) throw Rpc.error(NOT_FOUND, "bloque desconocido");
        if (req.getBytesWritten() != block.size()) throw Rpc.error(FAILED_PRECONDITION, "el tamaño no coincide con el plan");
        if (block.checksum() == null) {
          Db.update(c, "UPDATE dfs_block SET checksum_sha256 = ? WHERE block_id = ?", checksum, blockId);
        } else if (!block.checksum().equals(checksum)) {
          throw Rpc.error(FAILED_PRECONDITION, "el SHA-256 no coincide con el de la copia primaria");
        }
        int updated = Db.update(c, """
            UPDATE block_location SET state = 'WRITTEN', written_at = now()
            WHERE block_id = ? AND datanode_id = ? AND state <> 'DELETED'""", blockId, nodeId);
        if (updated == 0) throw Rpc.error(NOT_FOUND, "ese bloque no estaba asignado a este DataNode");
        return null;
      });
      return Rpc.ok("bloque registrado");
    });
  }

  /** El DataNode prueba quién es con su node_token (en la base está su SHA-256). */
  static void requireNodeToken(Connection c, UUID nodeId, String nodeToken) throws SQLException {
    if (Db.one(c, "SELECT 1 FROM datanode WHERE datanode_id = ? AND node_token_hash = ?",
        r -> 1, nodeId, Hash.sha256(nodeToken)) == null) {
      throw Rpc.error(UNAUTHENTICATED, "credenciales del DataNode inválidas");
    }
  }

  private record Block(long size, String checksum) { }
}
