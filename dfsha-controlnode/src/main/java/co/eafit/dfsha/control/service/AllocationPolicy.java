package co.eafit.dfsha.control.service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Selección determinista: ronda por archivo y luego puntuación 60/25/15. */
public final class AllocationPolicy {
  public record Candidate(UUID id, String endpoint, long capacityBytes, long freeBytes,
                          long reservedBytes, int activeTransfers, long storedBlocks) {
    public long availableBytes() { return freeBytes - reservedBytes; }
    Candidate reserve(long bytes) {
      return new Candidate(id, endpoint, capacityBytes, freeBytes, reservedBytes + bytes,
          activeTransfers, storedBlocks);
    }
  }

  public Optional<Candidate> select(List<Candidate> nodes, Map<UUID, Integer> assigned,
                                    long blockBytes, long reservedFloor,
                                    int maxTransfers, long targetBlocks) {
    int minimumRound = nodes.stream()
        .filter(n -> eligible(n, blockBytes, reservedFloor, maxTransfers))
        .mapToInt(n -> assigned.getOrDefault(n.id(), 0)).min().orElse(Integer.MAX_VALUE);
    return nodes.stream()
        .filter(n -> eligible(n, blockBytes, reservedFloor, maxTransfers))
        .filter(n -> assigned.getOrDefault(n.id(), 0) == minimumRound)
        .max(Comparator.comparingDouble((Candidate n) -> score(n, maxTransfers, targetBlocks))
            .thenComparing(n -> n.id().toString(), Comparator.reverseOrder()));
  }

  private static boolean eligible(Candidate node, long bytes, long floor, int maxTransfers) {
    return node.capacityBytes() > 0 && node.availableBytes() >= bytes + floor
        && node.activeTransfers() < maxTransfers;
  }

  static double score(Candidate node, int maxTransfers, long targetBlocks) {
    double space = clamp((double) node.availableBytes() / node.capacityBytes());
    double load = 1.0 - clamp((double) node.activeTransfers() / Math.max(1, maxTransfers));
    double blocks = 1.0 - clamp((double) node.storedBlocks() / Math.max(1, targetBlocks));
    return 0.60 * space + 0.25 * load + 0.15 * blocks;
  }

  private static double clamp(double value) { return Math.max(0.0, Math.min(1.0, value)); }
}
