package co.eafit.dfsha.datanode;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.SYNC;
import static java.nio.file.StandardOpenOption.WRITE;

import co.eafit.dfsha.common.Env;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import java.util.stream.Stream;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Los bloques en el disco del DataNode, cifrados con AES-256-GCM (cifrado en reposo).
 *
 * <p>Cada bloque es un archivo {@code <STORAGE_PATH>/<block_id>.blk} con: [IV de 12 bytes][datos cifrados
 * + etiqueta GCM de 16 bytes].
 * <ul>
 *   <li>El IV es aleatorio por bloque. La clave (STORAGE_KEY) es la misma en todos los DataNodes para que
 *       cualquiera pueda servir una réplica.</li>
 *   <li>GCM detecta si alguien modificó el archivo en disco: al leer, la etiqueta no cuadra y falla.</li>
 *   <li>El block_id va como "dato asociado": el archivo de un bloque no sirve renombrado como otro.</li>
 *   <li>Se escribe en un .tmp y se renombra: nunca queda un bloque a medias.</li>
 * </ul>
 * El SHA-256 se calcula siempre sobre los datos SIN cifrar (lo que el cliente subió).
 */
public final class BlockStore {
  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final Path dir;
  private final SecretKey key;
  private final SecureRandom random = new SecureRandom();

  public BlockStore(Path dir, byte[] key) throws IOException {
    if (key.length != 32) throw new IllegalArgumentException("STORAGE_KEY debe tener 32 bytes (AES-256)");
    this.dir = Files.createDirectories(dir);
    this.key = new SecretKeySpec(key, "AES");
  }

  /** STORAGE_PATH (carpeta de los bloques) y STORAGE_KEY(_FILE): 32 bytes en base64, de gen-secrets.sh. */
  public static BlockStore fromEnv(String nodeName) throws IOException {
    String key = Env.secret("STORAGE_KEY", null);
    if (key == null) throw new IllegalStateException("Falta STORAGE_KEY o STORAGE_KEY_FILE");
    return new BlockStore(Path.of(Env.get("STORAGE_PATH", "data/" + nodeName)), Base64.getDecoder().decode(key));
  }

  public void save(String blockId, byte[] data) throws IOException, GeneralSecurityException {
    Path target = file(blockId);     // validar antes de cifrar o crear cualquier archivo temporal
    byte[] iv = new byte[IV_BYTES];
    random.nextBytes(iv);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
    cipher.updateAAD(blockId.getBytes(UTF_8));
    byte[] encrypted = cipher.doFinal(data);

    byte[] stored = new byte[IV_BYTES + encrypted.length];
    System.arraycopy(iv, 0, stored, 0, IV_BYTES);
    System.arraycopy(encrypted, 0, stored, IV_BYTES, encrypted.length);
    Path tmp = dir.resolve(blockId + "." + UUID.randomUUID() + ".tmp");
    Files.write(tmp, stored, CREATE_NEW, WRITE, SYNC);              // SYNC: queda en disco antes de seguir
    Files.move(tmp, target, REPLACE_EXISTING, ATOMIC_MOVE);
  }

  /** Lee y descifra un bloque. Lanza NoSuchFileException si este nodo no lo tiene. */
  public byte[] load(String blockId) throws IOException, GeneralSecurityException {
    byte[] stored = Files.readAllBytes(file(blockId));
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, stored, 0, IV_BYTES));
    cipher.updateAAD(blockId.getBytes(UTF_8));
    return cipher.doFinal(stored, IV_BYTES, stored.length - IV_BYTES);
  }

  public void delete(String blockId) throws IOException {
    Files.deleteIfExists(file(blockId));
  }

  public long usedBytes() throws IOException {
    try (Stream<Path> files = blocks()) {
      return files.mapToLong(p -> p.toFile().length()).sum();
    }
  }

  public long blockCount() throws IOException {
    try (Stream<Path> files = blocks()) {
      return files.count();
    }
  }

  private Stream<Path> blocks() throws IOException {
    return Files.list(dir).filter(p -> p.toString().endsWith(".blk"));
  }

  /** El block_id tiene que ser un UUID: así nadie puede salirse de la carpeta con algo como "../". */
  private Path file(String blockId) {
    if (!UUID.fromString(blockId).toString().equals(blockId)) throw new IllegalArgumentException("block_id inválido");
    return dir.resolve(blockId + ".blk");
  }
}
