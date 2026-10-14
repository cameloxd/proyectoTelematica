package co.eafit.dfsha.datanode;

import co.eafit.dfsha.common.Env;
import io.grpc.Server;
import io.grpc.ServerBuilder;

import java.nio.file.Path;

/**
 * Punto de entrada del DataNode.
 * Ensambla el BlockStorageService y arranca el servidor gRPC.
 */
public final class DataNodeMain {

    public static void main(String[] args) throws Exception {
        int port = Env.getInt("GRPC_PORT", 50061);
        String storagePath = System.getenv().getOrDefault("STORAGE_PATH", "./data/blocks");

        BlockStorageService storage = new BlockStorageService(Path.of(storagePath));

        Server server = ServerBuilder.forPort(port)
                .addService(storage)
                .build()
                .start();

        System.out.println("DFSha DataNode escuchando en " + port);
        System.out.println("Almacenamiento en: " + Path.of(storagePath).toAbsolutePath());

        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown));
        server.awaitTermination();
    }
}