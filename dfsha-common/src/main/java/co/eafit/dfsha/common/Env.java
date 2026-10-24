package co.eafit.dfsha.common;

/** Lectura centralizada de variables de entorno con valores por defecto. */
public final class Env {
  private Env() { }

  public static String get(String name, String defaultValue) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? defaultValue : value;
  }

  public static int getInt(String name, int defaultValue) {
    return Integer.parseInt(get(name, Integer.toString(defaultValue)));
  }

  public static long getLong(String name, long defaultValue) {
    return Long.parseLong(get(name, Long.toString(defaultValue)));
  }
}
