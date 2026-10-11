package co.eafit.dfsha.common;

import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener;
import io.grpc.ServerCall;
import org.slf4j.MDC;

final class MdcServerCallListener<ReqT> extends SimpleForwardingServerCallListener<ReqT> {

    private final String key;
    private final String value;

    MdcServerCallListener(ServerCall.Listener<ReqT> delegate, String key, String value) {
        super(delegate);
        this.key = key;
        this.value = value;
    }

    private void withMdc(Runnable action) {
        MDC.put(key, value);
        try {
            action.run();
        } finally {
            MDC.remove(key);
        }
    }

    @Override public void onMessage(ReqT message) { withMdc(() -> super.onMessage(message)); }
    @Override public void onHalfClose()           { withMdc(super::onHalfClose); }
    @Override public void onCancel()              { withMdc(super::onCancel); }
    @Override public void onComplete()            { withMdc(super::onComplete); }
    @Override public void onReady()               { withMdc(super::onReady); }
}