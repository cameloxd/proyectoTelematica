package co.eafit.dfsha.client.blocks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Calcula cómo se parte un archivo en bloques de tamaño fijo.
 * NO lee los datos — solo calcula offsets, tamaños y hashes.
 */
public final class BlockSplitter {

    private BlockSplitter() {}

    /**
     * Calcula la lista de bloques de un archivo.
     *
     * @param file      archivo a particionar
     * @param blockSize tamaño de bloque en bytes (ej. 8 MiB = 8388608)
     * @return lista ordenada de BlockDescriptor con su SHA-256 ya calculado
     */
    public static List<BlockDescriptor> split(Path file, long blockSize) throws IOException {
        if (blockSize <= 0) {
            throw new IllegalArgumentException("blockSize debe ser > 0");
        }
        if (!Files.isRegularFile(file)) {
            throw new IOException("No es un archivo regular: " + file);
        }

        long fileSize = Files.size(file);
        List<BlockDescriptor> blocks = new ArrayList<>();

        if (fileSize == 0) {
            // Archivo vacío: sin bloques
            return blocks;
        }

        int numBlocks = (int) Math.ceil((double) fileSize / blockSize);
        long offset = 0;

        for (int i = 0; i < numBlocks; i++) {
            long size = Math.min(blockSize, fileSize - offset);
            String sha = BlockHasher.hashRange(file, offset, size);
            blocks.add(new BlockDescriptor(i, offset, size, sha));
            offset += size;
        }

        return blocks;
    }
}