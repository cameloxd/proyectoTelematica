package co.eafit.dfsha.control.model;

import java.util.List;
import java.util.UUID;

public record CommitResult(boolean committed, List<UUID> missingBlockIds) {
  public CommitResult { missingBlockIds = List.copyOf(missingBlockIds); }
}
