package co.eafit.dfsha.control.model;

import java.util.UUID;

public record BlockPlacement(UUID blockId, int blockIndex, long offsetBytes, long sizeBytes,
                             UUID dataNodeId, String dataNodeEndpoint, String checksumSha256) { }
