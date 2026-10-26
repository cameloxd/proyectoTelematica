package co.eafit.dfsha.common;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;

/**
 * Tokens firmados con Ed25519. Formato: base64url(payload) + "." + base64url(firma), donde
 * payload = "TIPO|sujeto|expira_epoch".
 *
 * <p>Solo el ControlNode tiene la clave privada (firma). Los DataNodes solo tienen la pública (verifican),
 * así que un DataNode no puede fabricar tokens. Ningún nodo guarda los tokens: basta verificar la firma.
 */
public final class Tokens {
  public static final String SESSION = "SESSION";   // sujeto = user_id   (lo emite Login)
  public static final String WRITE = "WRITE";       // sujeto = block_id  (AllocateFile)
  public static final String READ = "READ";         // sujeto = block_id  (GetFileManifest, réplicas)
  public static final String DELETE = "DELETE";     // sujeto = block_id

  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  private final PrivateKey privateKey;   // null en los DataNodes
  private final PublicKey publicKey;

  public Tokens(PrivateKey privateKey, PublicKey publicKey) {
    this.privateKey = privateKey;
    this.publicKey = publicKey;
  }

  /** Lee TOKEN_PUBLIC_KEY_FILE y, si existe, TOKEN_PRIVATE_KEY_FILE (los PEM de gen-secrets.sh). */
  public static Tokens fromEnv() {
    String privateFile = Env.get("TOKEN_PRIVATE_KEY_FILE", null);
    String publicFile = Env.get("TOKEN_PUBLIC_KEY_FILE", null);
    if (publicFile == null) throw new IllegalStateException("Falta TOKEN_PUBLIC_KEY_FILE");
    try {
      KeyFactory keys = KeyFactory.getInstance("Ed25519");
      PrivateKey privateKey = privateFile == null ? null
          : keys.generatePrivate(new PKCS8EncodedKeySpec(readPem(privateFile)));
      return new Tokens(privateKey, keys.generatePublic(new X509EncodedKeySpec(readPem(publicFile))));
    } catch (Exception e) {
      throw new IllegalStateException("No pude leer las claves Ed25519", e);
    }
  }

  /** Crea un token de {@code type} para {@code subject} que vence en {@code ttlSeconds}. */
  public String issue(String type, String subject, long ttlSeconds) {
    String payload = type + "|" + subject + "|" + expiresAt(ttlSeconds);
    try {
      Signature signer = Signature.getInstance("Ed25519");
      signer.initSign(privateKey);
      signer.update(payload.getBytes(UTF_8));
      return ENCODER.encodeToString(payload.getBytes(UTF_8)) + "." + ENCODER.encodeToString(signer.sign());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("No pude firmar el token", e);
    }
  }

  /** Devuelve el sujeto si el token es auténtico, del tipo pedido y no ha vencido. Si no, null. */
  public String verify(String token, String type) {
    try {
      String[] parts = token.split("\\.");
      if (parts.length != 2) return null;
      byte[] payload = DECODER.decode(parts[0]);
      Signature verifier = Signature.getInstance("Ed25519");
      verifier.initVerify(publicKey);
      verifier.update(payload);
      if (!verifier.verify(DECODER.decode(parts[1]))) return null;           // firma falsa u otra clave
      String[] fields = new String(payload, UTF_8).split("\\|");
      if (fields.length != 3 || !fields[0].equals(type)) return null;         // otro tipo de token
      if (Long.parseLong(fields[2]) < Instant.now().getEpochSecond()) return null;   // vencido
      return fields[1];
    } catch (Exception e) {
      return null;                                                           // token mal formado
    }
  }

  public static long expiresAt(long ttlSeconds) {
    return Instant.now().getEpochSecond() + ttlSeconds;
  }

  private static byte[] readPem(String file) throws java.io.IOException {
    String base64 = Files.readString(Path.of(file)).replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
    return Base64.getDecoder().decode(base64);
  }
}
