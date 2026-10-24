package co.eafit.dfsha.control;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PasswordsTest {
  @Test
  void verificaLaContrasenaCorrectaYRechazaLaIncorrecta() {
    String stored = Passwords.hash("secreta");
    assertTrue(Passwords.matches("secreta", stored));
    assertFalse(Passwords.matches("otra", stored));
  }

  @Test
  void mismaContrasenaDaHashesDistintos() {
    assertNotEquals(Passwords.hash("secreta"), Passwords.hash("secreta"));   // por la sal aleatoria
  }
}
