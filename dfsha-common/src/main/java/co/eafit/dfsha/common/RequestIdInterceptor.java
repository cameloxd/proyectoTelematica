package co.eafit.dfsha.common;   // el mismo package de tus otros archivos

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import java.util.UUID;

public final class RequestIdInterceptor implements ServerInterceptor {

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        // 1. ¿Vino un request_id del cliente? Si no, lo generamos aquí.
        String requestId = headers.get(Headers.REQUEST_ID);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }

        // 2. Lo guardamos en el Context de esta llamada.
        Context ctx = Context.current().withValue(Ctx.REQUEST_ID, requestId);

        // 3. Dejamos pasar la llamada con ese Context.
       return new MdcServerCallListener<>(
        Contexts.interceptCall(ctx, call, headers, next), "request_id", requestId);
    }
}