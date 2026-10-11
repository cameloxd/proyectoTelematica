package co.eafit.dfsha.common;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import java.util.UUID;
import java.util.function.Supplier;

public final class AuthClientInterceptor implements ClientInterceptor {

    private final Supplier<String> tokenSupplier;

    /** El token sale de un Supplier porque cambia después del Login. */
    public AuthClientInterceptor(Supplier<String> tokenSupplier) {
        this.tokenSupplier = tokenSupplier;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method,
            CallOptions callOptions,
            Channel next) {

        return new ForwardingClientCall.SimpleForwardingClientCall<>(
                next.newCall(method, callOptions)) {

            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                // 1. Token Bearer (si todavía no hay, por ejemplo antes del Login, no se manda).
                String token = tokenSupplier.get();
                if (token != null && !token.isBlank()) {
                    headers.put(Headers.AUTHORIZATION, "Bearer " + token);
                }

                // 2. request_id: reusa el de la llamada en curso si existe, si no genera uno.
                if (!headers.containsKey(Headers.REQUEST_ID)) {
                    String requestId = Ctx.REQUEST_ID.get();
                    if (requestId == null) requestId = UUID.randomUUID().toString();
                    headers.put(Headers.REQUEST_ID, requestId);
                }

                super.start(responseListener, headers);
            }
        };
    }
}