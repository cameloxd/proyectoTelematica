package co.eafit.dfsha.control.repository;

import co.eafit.dfsha.control.model.CommitResult;
import co.eafit.dfsha.control.model.FilePlan;
import co.eafit.dfsha.control.service.FileFlowException;
import java.util.UUID;

public interface FileFlowRepository {
  FilePlan allocate(UUID ownerId, String path, long sizeBytes, String operationId,
                    int blockSizeBytes, long pendingTtlSeconds) throws FileFlowException;
  CommitResult commit(UUID ownerId, UUID fileId, String operationId) throws FileFlowException;
  FilePlan manifest(UUID ownerId, String fileId, String path) throws FileFlowException;
  void reportStored(UUID dataNodeId, String nodeTokenHash, UUID blockId,
                    long bytesWritten, String checksumSha256) throws FileFlowException;
}
