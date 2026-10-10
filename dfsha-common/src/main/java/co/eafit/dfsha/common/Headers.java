package co.eafit.dfsha.common;
import io.grpc.Metadata;
public class Headers {
    
    private Headers () {}

    public static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    public static final Metadata.Key<String> REQUEST_ID =
            Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER);

    public static final Metadata.Key<String> OPERATION_ID =
            Metadata.Key.of("x-operation-id", Metadata.ASCII_STRING_MARSHALLER);

    public static final Metadata.Key<String> REGISTRATION_KEY =
            Metadata.Key.of("x-registration-key", Metadata.ASCII_STRING_MARSHALLER);
}
