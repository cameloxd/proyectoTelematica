package co.eafit.dfsha.client.blocks;

/**
 * Describe un bloque de un archivo particionado.
 *
 * @param blockIndex índice del bloque (0-based)
 * @param offset     byte donde comienza este bloque en el archivo original
 * @param size       tamaño del bloque en bytes
 * @param sha256     hash SHA-256 en hex del contenido del bloque (null si no se calculó aún)
 */
public record BlockDescriptor(int blockIndex, long offset, long size, String sha256) {
}