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

  /** SHA-256 de un archivo completo, leyéndolo por partes (sirve para archivos grandes). */
  public static String sha256(java.nio.file.Path file) throws java.io.IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (var in = new java.security.DigestInputStream(java.nio.file.Files.newInputStream(file), digest)) {
        in.transferTo(java.io.OutputStream.nullOutputStream());
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
