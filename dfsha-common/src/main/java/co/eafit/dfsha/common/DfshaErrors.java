package co.eafit.dfsha.common;

import io.grpc.StatusRuntimeException;
import io.grpc.Status;

public final  class DfshaErrors {

    private DfshaErrors() {}

    public static StatusRuntimeException notFound(String msg) {
        return Status.NOT_FOUND.withDescription(msg).asRuntimeException();
    }
    public static StatusRuntimeException alreadyExists(String msg) {
        return Status.ALREADY_EXISTS.withDescription(msg).asRuntimeException();
    }
    public static StatusRuntimeException invalidArgument(String msg) {
        return Status.INVALID_ARGUMENT.withDescription(msg).asRuntimeException();
    }
    public static StatusRuntimeException unauthenticated(String msg) {
        return Status.UNAUTHENTICATED.withDescription(msg).asRuntimeException();
    }
    public static StatusRuntimeException permissionDenied(String msg) {
        return Status.PERMISSION_DENIED.withDescription(msg).asRuntimeException();
    }
    public static StatusRuntimeException unavailable(String msg) {
        return Status.UNAVAILABLE.withDescription(msg).asRuntimeException();
    }
    public static StatusRuntimeException failedPrecondition(String msg) {
        return Status.FAILED_PRECONDITION.withDescription(msg).asRuntimeException();
    }


}
