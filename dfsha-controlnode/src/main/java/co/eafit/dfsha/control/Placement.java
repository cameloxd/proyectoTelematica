package co.eafit.dfsha.control;

import co.eafit.dfsha.common.Env;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Dónde se guarda cada bloque (heurística del Hito 1).
 *
 * <p>Copia primaria (AllocateFile):
 * <ol>
 *   <li>Solo compiten nodos ALIVE con espacio (disponible &gt;= bloque + RESERVED_BYTES) y con menos de
 *       MAX_TRANSFERS_PER_NODE transferencias en curso.</li>
 *   <li>Rondas: entre ellos solo juegan los que tienen MENOS bloques de este archivo. Así un archivo de
 *       3 bloques con 3 nodos queda en 3 nodos distintos.</li>
 *   <li>Gana el de mayor puntaje: 0.60 espacio libre + 0.25 poca carga + 0.15 pocos bloques.</li>
 * </ol>
 * Réplicas: los nodos de mayor puntaje que todavía no tienen una copia del bloque (nunca dos copias
 * en el mismo nodo).
 */
public final class Placement {

  /** Un DataNode candidato. {@code available} y {@code storedBlocks} se actualizan mientras se asigna. */
  public static final class Node {
    final UUID id;
    final String name;
    final long capacity;
    final int activeTransfers;
    long available;
    long storedBlocks;

    public Node(UUID id, String name, long capacity, long available, int activeTransfers, long storedBlocks) {
      this.id = id;
      this.name = name;
      this.capacity = capacity;
      this.available = available;
      this.activeTransfers = activeTransfers;
      this.storedBlocks = storedBlocks;
    }

    void assign(long bytes) {
      available -= bytes;
      storedBlocks++;
    }
  }

  private final long reservedBytes;   // colchón que nunca se asigna en cada nodo
  private final int maxTransfers;
  private final long targetBlocks;    // "nodo lleno" para el criterio de cantidad de bloques

  public Placement(long reservedBytes, int maxTransfers, long targetBlocks) {
    this.reservedBytes = reservedBytes;
    this.maxTransfers = maxTransfers;
    this.targetBlocks = targetBlocks;
  }

  public static Placement fromEnv() {
    return new Placement(Env.getLong("RESERVED_BYTES", 0), Env.getInt("MAX_TRANSFERS_PER_NODE", 8),
        Env.getLong("TARGET_BLOCKS_PER_NODE", 1000));
  }

  /** Nodos ALIVE. Espacio disponible = capacidad - lo que el ControlNode ya les asignó (PLANNED o WRITTEN). */
  public static List<Node> aliveNodes(Connection c) throws SQLException {
    return Db.query(c, """
        SELECT d.datanode_id, d.node_name, d.capacity_bytes, d.active_transfers,
               d.capacity_bytes - COALESCE(SUM(b.size_bytes), 0) AS available,
               COUNT(b.block_id) AS stored_blocks
        FROM datanode d
        LEFT JOIN block_location l ON l.datanode_id = d.datanode_id AND l.state IN ('PLANNED', 'WRITTEN')
        LEFT JOIN dfs_block b ON b.block_id = l.block_id
        WHERE d.status = 'ALIVE'
        GROUP BY d.datanode_id
        ORDER BY d.node_name""",
        r -> new Node(r.getObject(1, UUID.class), r.getString(2), r.getLong(3), r.getLong(5), r.getInt(4), r.getLong(6)));
  }

  /** Nodo para la copia primaria de un bloque, o null si ninguno puede recibirlo. */
  public Node choosePrimary(List<Node> nodes, Map<UUID, Integer> blocksOfThisFile, long bytes) {
    List<Node> eligible = nodes.stream().filter(n -> canTake(n, bytes)).toList();
    int round = eligible.stream().mapToInt(n -> blocksOfThisFile.getOrDefault(n.id, 0)).min().orElse(0);
    return eligible.stream()
        .filter(n -> blocksOfThisFile.getOrDefault(n.id, 0) == round)
        .min(bestFirst())
        .orElse(null);
  }

  /** Hasta {@code count} nodos para nuevas réplicas de un bloque, sin repetir los que ya tienen copia. */
  public List<Node> chooseReplicas(List<Node> nodes, Set<UUID> alreadyHaveIt, long bytes, int count) {
    return nodes.stream()
        .filter(n -> !alreadyHaveIt.contains(n.id) && canTake(n, bytes))
        .sorted(bestFirst())
        .limit(count)
        .toList();
  }

  private boolean canTake(Node n, long bytes) {
    return n.available >= bytes + reservedBytes && n.activeTransfers < maxTransfers;
  }

  /** Mayor puntaje primero; si empatan, por nombre (para que la decisión sea siempre la misma). */
  private Comparator<Node> bestFirst() {
    return Comparator.comparingDouble((Node n) -> -score(n)).thenComparing(n -> n.name);
  }

  double score(Node n) {
    double space = clamp((double) n.available / n.capacity);
    double load = 1 - clamp((double) n.activeTransfers / maxTransfers);
    double blocks = 1 - clamp((double) n.storedBlocks / targetBlocks);
    return 0.60 * space + 0.25 * load + 0.15 * blocks;
  }

  private static double clamp(double value) {
    return Math.max(0, Math.min(1, value));
  }
}
