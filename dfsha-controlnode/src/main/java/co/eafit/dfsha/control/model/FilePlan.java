package co.eafit.dfsha.control.model;

import java.util.List;
import java.util.UUID;

public record FilePlan(UUID fileId, String path, long sizeBytes, int blockSizeBytes,
                       String operationId, String status, List<BlockPlacement> blocks) {
  public FilePlan { blocks = List.copyOf(blocks); }
}
