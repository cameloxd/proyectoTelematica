package co.eafit.dfsha.control;

import co.eafit.dfsha.common.Env;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Acceso a PostgreSQL: un pool de conexiones y tres ayudas para no repetir código JDBC.
 *
 * <pre>
 *   db.tx(c -&gt; { ...varias consultas... })   todo o nada (commit o rollback)
 *   Db.query(c, sql, fila -&gt; ..., params)      SELECT que devuelve una lista
 *   Db.one(c, sql, fila -&gt; ..., params)        SELECT de una fila (o null)
 *   Db.update(c, sql, params)                 INSERT / UPDATE / DELETE
 * </pre>
 */
public final class Db implements AutoCloseable {
  private final HikariDataSource pool;

  /** Una o varias consultas que van juntas en la misma transacción. */
  public interface Work<T> {
    T run(Connection c) throws SQLException;
  }

  /** Convierte la fila actual del ResultSet en un objeto. */
  public interface Row<T> {
    T map(ResultSet r) throws SQLException;
  }

  /**
   * DB_URL puede tener dos hosts: jdbc:postgresql://primario,standby/dfsha?targetServerType=primary.
   * Así, si se promueve el standby, las conexiones nuevas encuentran solas al nuevo primario.
   */
  public static Db fromEnv() {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(Env.get("DB_URL", "jdbc:postgresql://localhost:5432/dfsha"));
    config.setUsername(Env.get("DB_USER", "dfsha"));
    config.setPassword(Env.secret("DB_PASSWORD", "dfsha"));
    config.setMaximumPoolSize(10);
    config.setInitializationFailTimeout(-1);   // arrancar aunque PostgreSQL todavía no responda
    config.setConnectionTimeout(5000);
    return new Db(new HikariDataSource(config));
  }

  private Db(HikariDataSource pool) {
    this.pool = pool;
  }

  public <T> T tx(Work<T> work) throws SQLException {
    try (Connection c = pool.getConnection()) {
      c.setAutoCommit(false);
      try {
        T result = work.run(c);
        c.commit();
        return result;
      } catch (SQLException | RuntimeException e) {
        c.rollback();
        throw e;
      }
    }
  }

  public static <T> List<T> query(Connection c, String sql, Row<T> row, Object... params) throws SQLException {
    try (PreparedStatement s = prepare(c, sql, params); ResultSet r = s.executeQuery()) {
      List<T> result = new ArrayList<>();
      while (r.next()) result.add(row.map(r));
      return result;
    }
  }

  public static <T> T one(Connection c, String sql, Row<T> row, Object... params) throws SQLException {
    List<T> result = query(c, sql, row, params);
    return result.isEmpty() ? null : result.get(0);
  }

  public static int update(Connection c, String sql, Object... params) throws SQLException {
    try (PreparedStatement s = prepare(c, sql, params)) {
      return s.executeUpdate();
    }
  }

  private static PreparedStatement prepare(Connection c, String sql, Object... params) throws SQLException {
    PreparedStatement s = c.prepareStatement(sql);
    for (int i = 0; i < params.length; i++) s.setObject(i + 1, params[i]);   // los ? se llenan en orden
    return s;
  }

  @Override
  public void close() {
    pool.close();
  }
}
