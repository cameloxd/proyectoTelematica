package co.eafit.dfsha.datanode;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.UUID;
import javax.crypto.AEADBadTagException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlockStoreTest {
  @TempDir
  Path dir;

  private BlockStore store() throws Exception {
    return new BlockStore(dir, new byte[32]);
  }

  @Test
  void guardaYLeeElMismoContenido() throws Exception {
    String id = UUID.randomUUID().toString();
    byte[] data = "hola DFSha".repeat(1000).getBytes(StandardCharsets.UTF_8);
    store().save(id, data);
    assertArrayEquals(data, store().load(id));
    assertEquals(1, store().blockCount());
  }

  @Test
  void enDiscoNoApareceElTextoOriginal() throws Exception {
    String id = UUID.randomUUID().toString();
    store().save(id, "TEXTO-SECRETO-DFSHA".repeat(100).getBytes(StandardCharsets.UTF_8));
    String onDisk = new String(Files.readAllBytes(dir.resolve(id + ".blk")), StandardCharsets.ISO_8859_1);
    assertFalse(onDisk.contains("TEXTO-SECRETO-DFSHA"));
  }

  @Test
  void detectaUnBloqueAlteradoEnDisco() throws Exception {
    String id = UUID.randomUUID().toString();
    store().save(id, new byte[1000]);
    Path file = dir.resolve(id + ".blk");
    byte[] stored = Files.readAllBytes(file);
    stored[100] ^= 1;
    Files.write(file, stored);
    assertThrows(AEADBadTagException.class, () -> store().load(id));
  }

  @Test
  void rechazaIdsQueNoSonUuid() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> store().save("../../etc/passwd", new byte[1]));
    assertThrows(NoSuchFileException.class, () -> store().load(UUID.randomUUID().toString()));
  }

  @Test
  void unIdInvalidoNoDejaArchivosTemporales() throws Exception {
    // Un id simple también debe rechazarse ANTES de crear un temporal dentro de la carpeta.
    assertThrows(IllegalArgumentException.class, () -> store().save("no-es-un-uuid", new byte[10]));
    try (var files = Files.list(dir)) {
      assertEquals(0, files.count());
    }
  }

  @Test
  void borrarDosVecesElMismoBloqueNoAfectaAOtroArchivo() throws Exception {
    var storage = store();
    String old = UUID.randomUUID().toString();
    String current = UUID.randomUUID().toString();
    storage.save(old, new byte[] {1});
    storage.save(current, new byte[] {2});
    storage.delete(old);
    storage.delete(old); // entrega repetida después de una confirmación ambigua
    assertEquals(1, storage.blockCount());
    assertArrayEquals(new byte[] {2}, storage.load(current));
  }

  @Test
  void reenvioIdenticoNoReescribeElBloque() throws Exception {
    String id = UUID.randomUUID().toString();
    var storage = store();
    storage.save(id, new byte[] {1, 2});
    byte[] encrypted = Files.readAllBytes(dir.resolve(id + ".blk"));
    store().save(id, new byte[] {1, 2});
    assertArrayEquals(encrypted, Files.readAllBytes(dir.resolve(id + ".blk")));
  }

  @Test
  void reenvioDistintoNoPuedeCorromperUnaCopiaValida() throws Exception {
    String id = UUID.randomUUID().toString();
    var storage = store();
    storage.save(id, new byte[] {1});
    assertThrows(java.io.IOException.class, () -> store().save(id, new byte[] {2}));
    assertArrayEquals(new byte[] {1}, storage.load(id));
  }

  @Test
  void unBorradoPrevioBloqueaEscriturasTardiasInclusoTrasReiniciar() throws Exception {
    String id = UUID.randomUUID().toString();
    store().delete(id); // incluso una tarea que llega antes de la escritura
    assertThrows(java.io.IOException.class, () -> store().save(id, new byte[] {1}));
    assertThrows(NoSuchFileException.class, () -> store().load(id));
    assertEquals(0, store().blockCount());
  }

  @Test
  void escritoresConcurrentesNoReemplazanContenido() throws Exception {
    String id = UUID.randomUUID().toString();
    var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
    var barrier = new java.util.concurrent.CyclicBarrier(2);
    try {
      var futures = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
      for (byte value : new byte[] {1, 2}) futures.add(executor.submit(() -> {
        barrier.await();
        try { store().save(id, new byte[] {value}); return true; }
        catch (java.io.IOException expected) { return false; }
      }));
      assertEquals(1, (futures.get(0).get() ? 1 : 0) + (futures.get(1).get() ? 1 : 0));
      assertEquals(1, store().blockCount());
    } finally { executor.shutdownNow(); }
  }

  @Test
  void borrarContraUnaEscrituraConcurrenteSiempreDejaElBloqueEliminado() throws Exception {
    var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      for (int i = 0; i < 10; i++) {
        String id = UUID.randomUUID().toString();
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        var write = executor.submit(() -> {
          barrier.await();
          try { store().save(id, new byte[] {1}); } catch (java.io.IOException deleted) { }
          return null;
        });
        var delete = executor.submit(() -> { barrier.await(); store().delete(id); return null; });
        write.get(); delete.get();
        assertFalse(Files.exists(dir.resolve(id + ".blk")));
        assertThrows(java.io.IOException.class, () -> store().save(id, new byte[] {1}));
      }
    } finally { executor.shutdownNow(); }
  }
}
