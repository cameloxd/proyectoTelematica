package co.eafit.dfsha.common;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 en hexadecimal: verifica que un bloque llegó intacto (cliente, DataNode y réplicas). */
public final class Hash {
  private Hash() { }

  public static String sha256(byte[] data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String sha256(String text) {
    return sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
}
