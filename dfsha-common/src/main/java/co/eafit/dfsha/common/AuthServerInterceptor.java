package co.eafit.dfsha.common;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.util.Optional;
import java.util.Set;

public final class AuthServerInterceptor implements ServerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenValidator validator;
    private final Set<String> publicMethods;   // nombres completos de métodos sin Bearer

    public AuthServerInterceptor(TokenValidator validator, Set<String> publicMethods) {
        this.validator = validator;
        this.publicMethods = publicMethods;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        // 1. Métodos públicos (Login, RegisterDataNode): pasan sin token.
        String method = call.getMethodDescriptor().getFullMethodName();
        if (publicMethods.contains(method)) {
            return next.startCall(call, headers);
        }

        // 2. ¿Viene "Authorization: Bearer ..."?
        String auth = headers.get(Headers.AUTHORIZATION);
        if (auth == null || !auth.startsWith(BEARER_PREFIX)) {
            return reject(call, "Falta el token Bearer");
        }

        // 3. ¿El token es válido?
        Optional<String> userId = validator.validate(auth.substring(BEARER_PREFIX.length()).trim());
        if (userId.isEmpty()) {
            return reject(call, "Token inválido o vencido");
        }

        // 4. Dejamos el user_id en el Context y pasamos la llamada.
        Context ctx = Context.current().withValue(Ctx.USER_ID, userId.get());
        return new MdcServerCallListener<>(
        Contexts.interceptCall(ctx, call, headers, next), "user_id", userId.get());
    }

    private static <ReqT, RespT> ServerCall.Listener<ReqT> reject(
            ServerCall<ReqT, RespT> call, String message) {
        call.close(Status.UNAUTHENTICATED.withDescription(message), new Metadata());
        return new ServerCall.Listener<>() {};   // listener vacío: no se procesa nada
    }
}