package co.eafit.dfsha.client.blocks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Prueba de humo de M1.2:
 * 1. Parte test-20mib.bin con blockSize = 8 MiB
 * 2. Imprime cada bloque (índice, offset, size, sha256)
 * 3. Reensambla a un archivo nuevo
 * 4. Compara SHA-256 del original y del reconstruido
 */
public class BlockSmokeTest {

    private static final long BLOCK_SIZE = 8L * 1024 * 1024; // 8 MiB

    public static void main(String[] args) throws Exception {
        Path original = Path.of("test-20mib.bin");
        Path rebuilt  = Path.of("test-20mib-rebuilt.bin");

        if (!Files.exists(original)) {
            System.err.println("No existe " + original.toAbsolutePath());
            System.exit(1);
        }

        System.out.println("== Split ==");
        List<BlockDescriptor> blocks = BlockSplitter.split(original, BLOCK_SIZE);
        for (BlockDescriptor b : blocks) {
            System.out.printf("  bloque %d: offset=%d size=%d sha256=%s%n",
                    b.blockIndex(), b.offset(), b.size(),
                    b.sha256().substring(0, 16) + "...");
        }
        System.out.println("  total bloques: " + blocks.size());

        System.out.println("\n== Assemble ==");
        long written = FileAssembler.assemble(original, rebuilt, BLOCK_SIZE);
        System.out.println("  bytes escritos: " + written);

        System.out.println("\n== Verify ==");
        String shaOriginal = BlockHasher.hashFile(original);
        String shaRebuilt  = BlockHasher.hashFile(rebuilt);
        System.out.println("  sha original:   " + shaOriginal);
        System.out.println("  sha rebuilt:    " + shaRebuilt);

        if (shaOriginal.equals(shaRebuilt)) {
            System.out.println("\nOK: el archivo reconstruido es idéntico al original.");
        } else {
            System.out.println("\nFALLO: los hashes NO coinciden.");
            System.exit(2);
        }
    }
}