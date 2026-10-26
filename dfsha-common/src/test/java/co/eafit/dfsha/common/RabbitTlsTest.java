package co.eafit.dfsha.common;
import static org.junit.jupiter.api.Assertions.*;
import com.rabbitmq.client.ConnectionFactory;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class RabbitTlsTest {
  @TempDir Path dir;
  @Test void missingCaFailsClosedRatherThanTrustingAllCertificates() {
    var factory = new ConnectionFactory();
    assertThrows(IllegalStateException.class, () -> Rabbit.configureTls(factory, dir.resolve("missing.pem")));
    assertFalse(factory.isSSL());
  }
  @Test void invalidCaCannotEnableAnUnverifiedConnection() throws Exception {
    Path ca = dir.resolve("invalid.pem"); Files.writeString(ca, "not a certificate");
    var factory = new ConnectionFactory();
    assertThrows(IllegalStateException.class, () -> Rabbit.configureTls(factory, ca));
    assertFalse(factory.isSSL());
  }
}
