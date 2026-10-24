package co.eafit.dfsha.control;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Contraseñas con PBKDF2-HMAC-SHA256 (viene en el JDK). Se guarda "iteraciones:sal:hash"; la sal
 * aleatoria hace que dos usuarios con la misma contraseña tengan hashes distintos.
 */
public final class Passwords {
  private static final int ITERATIONS = 100_000;
  private static final SecureRandom RANDOM = new SecureRandom();

  private Passwords() { }

  public static String hash(String password) {
    byte[] salt = new byte[16];
    RANDOM.nextBytes(salt);
    return ITERATIONS + ":" + b64(salt) + ":" + b64(pbkdf2(password, salt, ITERATIONS));
  }

  public static boolean matches(String password, String stored) {
    String[] parts = stored.split(":");
    byte[] expected = Base64.getDecoder().decode(parts[2]);
    byte[] actual = pbkdf2(password, Base64.getDecoder().decode(parts[1]), Integer.parseInt(parts[0]));
    return MessageDigest.isEqual(expected, actual);   // comparación en tiempo constante
  }

  private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
    try {
      PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
      return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String b64(byte[] bytes) {
    return Base64.getEncoder().encodeToString(bytes);
  }
}
