package co.eafit.dfsha.control.security;

import co.eafit.dfsha.common.Env;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** Tokens v1 firmados con Ed25519: base64url(payload).base64url(signature). */
public final class BlockTokenSigner {
  public enum Action { WRITE, READ }
  private final PrivateKey privateKey;
  private final Clock clock;
  private final long ttlSeconds;

  public BlockTokenSigner(PrivateKey privateKey, Clock clock, long ttlSeconds) {
    this.privateKey = privateKey; this.clock = clock; this.ttlSeconds = ttlSeconds;
  }

  public static BlockTokenSigner fromEnvironment() {
    String file = Env.get("TOKEN_PRIVATE_KEY_FILE", "");
    if (file.isBlank()) throw new IllegalStateException("TOKEN_PRIVATE_KEY_FILE es obligatorio");
    try {
      String pem = Files.readString(Path.of(file)).replace("-----BEGIN PRIVATE KEY-----", "")
          .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
      PrivateKey key = KeyFactory.getInstance("Ed25519")
          .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
      return new BlockTokenSigner(key, Clock.systemUTC(), Env.getLong("BLOCK_TOKEN_TTL_SECONDS", 900));
    } catch (Exception e) {
      throw new IllegalStateException("No se pudo cargar la clave privada Ed25519", e);
    }
  }

  public SignedToken issue(Action action, UUID blockId, UUID fileId, UUID ownerId) {
    long expiresAt = Instant.now(clock).plusSeconds(ttlSeconds).getEpochSecond();
    String payload = String.join("|", "v1", action.name(), blockId.toString(), fileId.toString(),
        ownerId.toString(), Long.toString(expiresAt));
    try {
      Signature signer = Signature.getInstance("Ed25519"); signer.initSign(privateKey);
      signer.update(payload.getBytes(StandardCharsets.UTF_8));
      Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
      return new SignedToken(encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8))
          + "." + encoder.encodeToString(signer.sign()), expiresAt);
    } catch (Exception e) {
      throw new IllegalStateException("No se pudo firmar el token de bloque", e);
    }
  }

  public record SignedToken(String value, long expiresAtEpochSeconds) { }
}
