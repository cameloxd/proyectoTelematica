package co.eafit.dfsha.control.db;

import co.eafit.dfsha.common.Env;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;

/** Pool de conexiones del ControlNode. Las credenciales nunca se registran en logs. */
public final class Database implements AutoCloseable {
  private final HikariDataSource dataSource;

  private Database(HikariDataSource dataSource) { this.dataSource = dataSource; }

  public static Database fromEnvironment() {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(Env.get("DB_URL", "jdbc:postgresql://localhost:5432/dfsha"));
    config.setUsername(Env.get("DB_USER", "dfsha"));
    config.setPassword(Env.get("DB_PASSWORD", readPasswordFile()));
    config.setMaximumPoolSize(Env.getInt("DB_POOL_SIZE", 10));
    config.setMinimumIdle(1);
    config.setPoolName("dfsha-controlnode");
    return new Database(new HikariDataSource(config));
  }

  private static String readPasswordFile() {
    String file = System.getenv("DB_PASSWORD_FILE");
    if (file == null || file.isBlank()) return "dfsha";
    try { return java.nio.file.Files.readString(java.nio.file.Path.of(file)).trim(); }
    catch (java.io.IOException e) { throw new IllegalStateException("No se pudo leer DB_PASSWORD_FILE", e); }
  }

  public DataSource dataSource() { return dataSource; }
  @Override public void close() { dataSource.close(); }
}
