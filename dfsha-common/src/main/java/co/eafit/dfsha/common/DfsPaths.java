package co.eafit.dfsha.common;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Rutas del DFS, estilo Linux. El cliente las usa para resolver rutas relativas (cd, ..) y el
 * ControlNode para validar lo que recibe. Siempre devuelven la forma normal: "/a/b" (sin "/" final).
 */
public final class DfsPaths {
  private DfsPaths() { }

  /** Resuelve {@code input} (absoluta o relativa) desde el directorio actual {@code cwd}. */
  public static String resolve(String cwd, String input) {
    String full = input.startsWith("/") ? input : cwd + "/" + input;
    Deque<String> parts = new ArrayDeque<>();
    for (String part : full.split("/")) {
      if (part.isEmpty() || part.equals(".")) continue;      // "//" y "." no cambian nada
      if (part.equals("..")) { parts.pollLast(); continue; }  // ".." sube un nivel ("/.." sigue en "/")
      parts.addLast(part);
    }
    return "/" + String.join("/", parts);
  }

  /** Directorio padre: "/a/b" -> "/a", "/a" -> "/". */
  public static String parent(String path) {
    int i = path.lastIndexOf('/');
    return i <= 0 ? "/" : path.substring(0, i);
  }

  /** Último componente: "/a/b.txt" -> "b.txt". */
  public static String name(String path) {
    return path.substring(path.lastIndexOf('/') + 1);
  }
}
