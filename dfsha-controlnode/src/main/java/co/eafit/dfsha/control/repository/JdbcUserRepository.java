package co.eafit.dfsha.control.repository;

import java.sql.*;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** Persistencia de usuarios; el hash se recibe ya calculado por la capa de autenticación. */
public final class JdbcUserRepository {
  private final DataSource dataSource;
  public JdbcUserRepository(DataSource dataSource) { this.dataSource = dataSource; }
  public UUID create(String username, String passwordHash) throws SQLException {
    try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(
        "INSERT INTO app_user (username, password_hash) VALUES (?, ?) RETURNING user_id")) {
      s.setString(1, username); s.setString(2, passwordHash); try (ResultSet r = s.executeQuery()) { r.next(); return r.getObject(1, UUID.class); }
    }
  }
  public Optional<User> findByUsername(String username) throws SQLException {
    try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(
        "SELECT user_id, username, password_hash, active FROM app_user WHERE username = ?")) {
      s.setString(1, username); try (ResultSet r = s.executeQuery()) {
        return r.next() ? Optional.of(new User(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getBoolean(4))) : Optional.empty();
      }
    }
  }
  public record User(UUID id, String username, String passwordHash, boolean active) { }
}
