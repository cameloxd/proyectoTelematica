package co.eafit.dfsha.client.blocks;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Utilidades para calcular SHA-256 de bloques.
 */
public final class BlockHasher {

    private BlockHasher() {}

    /**
     * Calcula el SHA-256 de un rango [offset, offset+size) de un archivo.
     * Lee solo ese rango, sin cargar el archivo entero en memoria.
     */
    public static String hashRange(Path file, long offset, long size) throws IOException {
        MessageDigest md = newDigest();
        byte[] buffer = new byte[64 * 1024]; // 64 KiB

        try (InputStream in = Files.newInputStream(file)) {
            skipFully(in, offset);

            long remaining = size;
            while (remaining > 0) {
                int toRead = (int) Math.min(buffer.length, remaining);
                int read = in.read(buffer, 0, toRead);
                if (read == -1) {
                    throw new IOException("EOF inesperado leyendo " + file + " en offset " + offset);
                }
                md.update(buffer, 0, read);
                remaining -= read;
            }
        }
        return toHex(md.digest());
    }

    /**
     * Calcula el SHA-256 de todo un archivo.
     */
    public static String hashFile(Path file) throws IOException {
        MessageDigest md = newDigest();
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                md.update(buffer, 0, read);
            }
        }
        return toHex(md.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible en esta JVM", e);
        }
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        long remaining = n;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                // Fallback por si skip() devuelve 0
                if (in.read() == -1) throw new IOException("EOF al saltar " + n + " bytes");
                remaining--;
            } else {
                remaining -= skipped;
            }
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}