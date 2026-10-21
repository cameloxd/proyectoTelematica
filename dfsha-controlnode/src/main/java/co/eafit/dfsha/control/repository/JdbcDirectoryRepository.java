package co.eafit.dfsha.control.repository;

import java.sql.*;
import java.util.*;
import javax.sql.DataSource;

public final class JdbcDirectoryRepository {
  private final DataSource dataSource;
  public JdbcDirectoryRepository(DataSource dataSource) { this.dataSource = dataSource; }
  public UUID create(UUID ownerId, String path) throws SQLException {
    try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(
        "INSERT INTO dfs_directory (owner_id, path) VALUES (?, ?) RETURNING directory_id")) {
      s.setObject(1, ownerId); s.setString(2, path); try (ResultSet r = s.executeQuery()) { r.next(); return r.getObject(1, UUID.class); }
    }
  }
  public List<Directory> listChildren(UUID ownerId, String parentPath) throws SQLException {
    List<Directory> result = new ArrayList<>();
    try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(
        "SELECT directory_id, path FROM dfs_directory WHERE owner_id=? AND parent_path=? AND deleted_at IS NULL ORDER BY path")) {
      s.setObject(1, ownerId); s.setString(2, parentPath); try (ResultSet r = s.executeQuery()) {
        while (r.next()) result.add(new Directory(r.getObject(1, UUID.class), r.getString(2)));
      }
    } return result;
  }
  public record Directory(UUID id, String path) { }
}
