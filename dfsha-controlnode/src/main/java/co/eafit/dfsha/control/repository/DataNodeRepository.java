package co.eafit.dfsha.control.repository;

import co.eafit.dfsha.control.model.DataNode;
import java.sql.SQLException;
import java.util.Optional;

public interface DataNodeRepository {
  /** Registra o actualiza un nodo por nombre sin tocar las reservas pendientes. */
  DataNode register(String name, String endpoint, String internalEndpoint, long capacityBytes) throws SQLException;
  Optional<DataNode> findByName(String name) throws SQLException;
}
