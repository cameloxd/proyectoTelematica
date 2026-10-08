package co.eafit.dfsha.common;

/** Lectura de configuracion por variables de entorno con valor por defecto. */
public final class Env {
  private Env() {}

  public static String get(String name, String defaultValue) {
    String v = System.getenv(name);
    return (v == null || v.isBlank()) ? defaultValue : v;
  }

  public static int getInt(String name, int defaultValue) {
    return Integer.parseInt(get(name, Integer.toString(defaultValue)));
  }
}
