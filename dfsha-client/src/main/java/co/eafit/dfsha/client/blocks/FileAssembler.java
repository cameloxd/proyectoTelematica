package co.eafit.dfsha.client.blocks;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class FileAssembler {

    private FileAssembler() {}

    /**
     * Copia el archivo fuente al destino bloque por bloque, usando FileChannel
     * y escribiendo en offsets exactos. Es un "reensamblado por copia".
     */
    public static long assemble(Path sourceFile, Path targetFile, long blockSize) throws IOException {
        if (blockSize <= 0) throw new IllegalArgumentException("blockSize debe ser > 0");
        if (Files.exists(targetFile)) Files.delete(targetFile);

        long fileSize = Files.size(sourceFile);

        try (FileChannel in  = FileChannel.open(sourceFile, StandardOpenOption.READ);
             FileChannel out = FileChannel.open(targetFile,
                     StandardOpenOption.CREATE,
                     StandardOpenOption.WRITE)) {

            long offset = 0;
            while (offset < fileSize) {
                long chunk = Math.min(blockSize, fileSize - offset);
                ByteBuffer buf = ByteBuffer.allocate((int) chunk);
                int read = in.read(buf, offset);
                if (read <= 0) throw new IOException("EOF en offset " + offset);
                buf.flip();
                long pos = offset;
                while (buf.hasRemaining()) {
                    pos += out.write(buf, pos);
                }
                offset += read;
            }
        }
        return fileSize;
    }

    /**
     * Escribe un bloque en su offset exacto del archivo destino.
     * Se usa en Fase 2 cuando los bloques llegan de distintos DataNodes.
     */
    public static void writeBlockAt(Path targetFile, long offset, Path blockFile) throws IOException {
        try (FileChannel out = FileChannel.open(targetFile,
                     StandardOpenOption.CREATE,
                     StandardOpenOption.WRITE);
             InputStream in = Files.newInputStream(blockFile)) {

            byte[] buffer = new byte[64 * 1024];
            long pos = offset;
            int read;
            while ((read = in.read(buffer)) != -1) {
                ByteBuffer bb = ByteBuffer.wrap(buffer, 0, read);
                while (bb.hasRemaining()) {
                    pos += out.write(bb, pos);
                }
            }
        }
    }
}