package co.eafit.dfsha.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import org.junit.jupiter.api.Test;

class TokensTest {
  private final KeyPair keys = newKeys();
  private final Tokens controlNode = new Tokens(keys.getPrivate(), keys.getPublic());
  private final Tokens dataNode = new Tokens(null, keys.getPublic());      // solo clave pública

  @Test
  void tokenValidoDevuelveElSujeto() {
    String token = controlNode.issue(Tokens.WRITE, "bloque-1", 60);
    assertEquals("bloque-1", dataNode.verify(token, Tokens.WRITE));
  }

  @Test
  void otroTipoDeTokenNoSirve() {
    String read = controlNode.issue(Tokens.READ, "bloque-1", 60);
    assertNull(dataNode.verify(read, Tokens.WRITE));
  }

  @Test
  void tokenVencidoNoSirve() {
    assertNull(dataNode.verify(controlNode.issue(Tokens.READ, "bloque-1", -1), Tokens.READ));
  }

  @Test
  void tokenFirmadoConOtraClaveNoSirve() {
    KeyPair other = newKeys();
    String forged = new Tokens(other.getPrivate(), other.getPublic()).issue(Tokens.READ, "bloque-1", 60);
    assertNull(dataNode.verify(forged, Tokens.READ));
  }

  @Test
  void tokenModificadoNoSirve() {
    String token = controlNode.issue(Tokens.READ, "bloque-1", 60);
    String otherBlock = java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString(("READ|bloque-2|" + Tokens.expiresAt(60)).getBytes());
    assertNull(dataNode.verify(otherBlock + token.substring(token.indexOf('.')), Tokens.READ));
    assertNull(dataNode.verify("basura", Tokens.READ));
  }

  private static KeyPair newKeys() {
    try {
      return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
