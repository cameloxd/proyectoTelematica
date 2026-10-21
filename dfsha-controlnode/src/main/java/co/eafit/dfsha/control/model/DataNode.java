package co.eafit.dfsha.control.model;

import java.util.UUID;

public record DataNode(UUID id, String name, String endpoint, String internalEndpoint,
                       String status, long capacityBytes, long freeBytes,
                       long reservedBytes, int activeTransfers, long storedBlocks) { }
