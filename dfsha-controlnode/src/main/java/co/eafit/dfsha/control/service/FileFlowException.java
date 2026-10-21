package co.eafit.dfsha.control.service;

public final class FileFlowException extends Exception {
  private static final long serialVersionUID = 1L;

  public enum Kind { INVALID_ARGUMENT, NOT_FOUND, ALREADY_EXISTS, RESOURCE_EXHAUSTED,
                     FAILED_PRECONDITION, UNAUTHENTICATED, INTERNAL }
  private final Kind kind;

  public FileFlowException(Kind kind, String message) { super(message); this.kind = kind; }
  public FileFlowException(Kind kind, String message, Throwable cause) { super(message, cause); this.kind = kind; }
  public Kind kind() { return kind; }
}
