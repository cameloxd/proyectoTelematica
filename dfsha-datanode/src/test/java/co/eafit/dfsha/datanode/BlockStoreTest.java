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
}
