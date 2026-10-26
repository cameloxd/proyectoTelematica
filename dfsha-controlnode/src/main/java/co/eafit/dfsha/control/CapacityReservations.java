package co.eafit.dfsha.control;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Un solo candado transaccional para toda reserva: primarias y réplicas.
 * El espacio reservado se deriva de block_location; no se mantienen contadores paralelos.
 */
final class CapacityReservations {
  private CapacityReservations() { }

  static void lock(Connection c) throws SQLException {
    Db.one(c, "SELECT pg_advisory_xact_lock(1)", r -> 1);
  }
}
