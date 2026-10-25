package co.eafit.dfsha.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DfsPathsTest {
  @Test
  void resuelveRutasRelativasYAbsolutas() {
    assertEquals("/docs/a.txt", DfsPaths.resolve("/docs", "a.txt"));
    assertEquals("/a.txt", DfsPaths.resolve("/docs", "/a.txt"));
    assertEquals("/", DfsPaths.resolve("/docs", ".."));
    assertEquals("/", DfsPaths.resolve("/", "../.."));
    assertEquals("/x/z", DfsPaths.resolve("/x/y", "./../z/"));
    assertEquals("/a/b", DfsPaths.resolve("/", "//a///b"));
  }

  @Test
  void padreYNombre() {
    assertEquals("/a", DfsPaths.parent("/a/b"));
    assertEquals("/", DfsPaths.parent("/a"));
    assertEquals("b.txt", DfsPaths.name("/a/b.txt"));
  }
}
