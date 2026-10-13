package co.eafit.dfsha.common.mq;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public record RabbitConfig(String host, int port, String username, String password) {

    public static RabbitConfig fromEnv() {
        return new RabbitConfig(
                env("RABBITMQ_HOST", "localhost"),
                Integer.parseInt(env("RABBITMQ_PORT", "5672")),
                env("RABBITMQ_USER", "dfsha"),
                readPassword());
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }

    /** En Docker la contraseña llega como archivo (secret); en local, como variable. */
    private static String readPassword() {
        String file = System.getenv("RABBITMQ_PASSWORD_FILE");
        if (file != null && !file.isBlank()) {
            try {
                return Files.readString(Path.of(file)).trim();
            } catch (IOException e) {
                throw new IllegalStateException("No pude leer RABBITMQ_PASSWORD_FILE: " + file, e);
            }
        }
        String pass = System.getenv("RABBITMQ_PASSWORD");
        if (pass == null || pass.isBlank()) {
            throw new IllegalStateException("Falta RABBITMQ_PASSWORD o RABBITMQ_PASSWORD_FILE");
        }
        return pass;
    }

    // El toString de un record imprime todos los campos; así nunca se loguea la contraseña.
    @Override
    public String toString() {
        return "RabbitConfig[host=" + host + ", port=" + port + ", username=" + username + "]";
    }
}