package co.eafit.dfsha.common;

import java.util.Optional;

@FunctionalInterface
public interface TokenValidator {

    /** Devuelve el user_id si el token es válido, o vacío si no lo es. */
    Optional<String> validate(String token);

    /**
     * SOLO DESARROLLO: acepta cualquier token no vacío.
     * Se reemplaza por el validador real cuando esté el Login (tarea C1.4 de Camilo).
     */
    static TokenValidator permissiveForDev() {
        return token -> (token == null || token.isBlank())
                ? Optional.empty()
                : Optional.of("dev-user");
    }
}