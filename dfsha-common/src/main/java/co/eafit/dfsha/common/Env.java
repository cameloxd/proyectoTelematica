package co.eafit.dfsha.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Configuración por variables de entorno. Así se configura cada contenedor en docker-compose.yml.
 */
public final class Env {
  private Env() { }

  /** Valor de la variable, o {@code defaultValue} si no existe o está vacía. */
  public static String get(String name, String defaultValue) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? defaultValue : value.trim();
  }

  public static int getInt(String name, int defaultValue) {
    return Integer.parseInt(get(name, String.valueOf(defaultValue)));
  }

  public static long getLong(String name, long defaultValue) {
    return Long.parseLong(get(name, String.valueOf(defaultValue)));
  }

  /** Lista separada por comas: "a:1,b:2" -> [a:1, b:2]. Se usa para los endpoints de los ControlNodes. */
  public static List<String> getList(String name, String defaultValue) {
    return Arrays.stream(get(name, defaultValue).split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
  }

  /**
   * Lee un secreto. En Docker los secretos llegan como archivo: si existe NAME_FILE se lee ese archivo;
   * si no, se usa la variable NAME (cómodo para pruebas locales).
   */
  public static String secret(String name, String defaultValue) {
    String file = get(name + "_FILE", null);
    if (file == null) return get(name, defaultValue);
    try {
      return Files.readString(Path.of(file)).trim();
    } catch (IOException e) {
      throw new IllegalStateException("No pude leer " + name + "_FILE: " + file, e);
    }
  }
}
