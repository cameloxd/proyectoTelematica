package co.eafit.dfsha.common;

import io.grpc.Context;

public class Ctx {


    private Ctx() {}

    public static final Context.Key<String> REQUEST_ID = Context.key("request-id");
    public static final Context.Key<String> USER_ID    = Context.key("user-id");
}
