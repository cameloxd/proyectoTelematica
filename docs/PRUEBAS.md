# Comprobación de DFSha

El resultado vigente está en [ESTADO-DEL-PROYECTO.md](ESTADO-DEL-PROYECTO.md). Este documento mantiene los comandos y el alcance; no duplica el historial de resultados.

## 1. Pruebas unitarias y compilación

Desde la raíz del repositorio, con Java 17+ y Maven 3.9+:

```bash
mvn test
mvn package
```

Sin `DFSHA_TEST_DB_URL`, JUnit omite intencionalmente las clases de PostgreSQL. Revisar siempre `Skipped`: un build verde con esos casos omitidos no valida la persistencia ni las reservas.

Las pruebas RPC actuales se ejecutan sin TLS. Usar una terminal de pruebas sin certificados de servidor exportados; si ya existe `deploy/secrets/ca.crt`, definir `TLS_CA_FILE=none` solo en esa terminal para evitar que el cliente de prueba intente TLS contra un servidor local en texto plano. No conservar esa configuración para el cliente del clúster TLS.

`-DskipTests` solo sirve para compilar/empaquetar: no se acepta como evidencia de que las pruebas pasan. El Dockerfile lo utiliza durante la construcción y por eso una imagen construida tampoco prueba la batería.

## 2. Integración con PostgreSQL real

Usar una base de pruebas existente, con un usuario que pueda crear esquemas. No apuntar a producción. Cada caso crea `dfsha_c2_<uuid>`, carga `deploy/schema.sql` seguido de `deploy/migrations/001-block-delete-outbox.sql` y elimina únicamente ese esquema al terminar; no trunca tablas ajenas.

Ejemplo PowerShell, adaptando URL y credenciales locales:

```powershell
$env:DFSHA_TEST_DB_URL = 'jdbc:postgresql://localhost:5432/dfsha_test'
$env:DFSHA_TEST_DB_USER = 'dfsha'
$env:DFSHA_TEST_DB_PASSWORD = 'tu-clave-local'
$env:BLOCK_SIZE_BYTES = '8388608'
$env:RESERVED_BYTES = '0'
$env:MAX_TRANSFERS_PER_NODE = '8'
$env:TARGET_BLOCKS_PER_NODE = '1000'
$env:REPLICATION_FACTOR = '3'
$env:TLS_CA_FILE = 'none'
mvn test
```

Estas variables son de la terminal de pruebas, no se deben guardar en Git. Abrir otra terminal con la configuración TLS normal al pasar al clúster. No se inicia PostgreSQL automáticamente desde Maven.

Clases relevantes:

- `ControlFileFlowPostgresTest`: autenticación, aislamiento, partición, reportes, commit/manifiesto, reservas, idempotencia y concurrencia.
- `ClusterMonitorPostgresTest`: planificador real de réplicas, exclusión de nodos que ya tienen copia, no duplicación del plan y capacidad compartida con primarias.
- Ambas reutilizan `PostgresControlFixture`: un único fixture, esquema aislado y servidor gRPC local. No se crea un segundo repositorio ficticio de metadatos.
- `DeleteOutboxPostgresTest` reutiliza el mismo fixture: atomicidad de borrado/tarea, fallos SQL, transporte no disponible, reinicio del trabajador, espera entre intentos, recuperación de filas antiguas mediante migración, concurrencia entre trabajadores, entrega repetida tras confirmación ambigua y ciclo de vida del trabajador de fondo. Solo el transporte se inyecta; no demuestra conexión con un broker real ni eliminación distribuida.
- `RabbitDeletePublishTest` (common) comprueba el contrato AMQP con un canal simulado: cola durable, mensaje persistente y obligatorio, confirmación, retorno sin ruta, timeout y nack. No es un clúster RabbitMQ.

No requieren RabbitMQ para planificar. La prueba del monitor reserva metadatos y devuelve tareas; no las publica ni hace la copia física.

## 3. Almacenamiento

`BlockStoreTest` usa directorios temporales, comprueba lectura/cifrado, alteración GCM y rechazo de identificadores antes de crear archivos. No necesita secretos del despliegue.

También comprueba que entregar dos veces el mismo borrado no elimina otro bloque. Esto valida el comportamiento local de `deleteIfExists`, no el acuse del consumidor RabbitMQ.

En entornos Windows restringidos puede ser necesario situar el temporal del JVM de pruebas en una carpeta permitida. Por ejemplo, después de crear una carpeta local `target/test-temp`:

```powershell
mvn '-DargLine=-Djava.io.tmpdir=C:/ruta/absoluta/permitida/test-temp' test
```

Pasar `java.io.tmpdir` al inicio del JVM evita confundir errores de permisos del sandbox con defectos de almacenamiento. No modificar el algoritmo para silenciar esos errores ni ignorar sus tests.

## 4. Evidencia end-to-end: etapa posterior

Los comandos de arranque y los siete escenarios están en [GUIA-DEL-CODIGO.md](GUIA-DEL-CODIGO.md#7-cómo-correr-y-probar).

`deploy/escenarios.sh` detiene servicios y promueve PostgreSQL: ejecutar únicamente en un clúster de pruebas y con autorización. Guardar su resultado junto con el commit, entorno, factor de réplica y checksums usados.

Las pruebas unitarias/JDBC no demuestran por sí solas TLS real, replicación física, failover PostgreSQL, borrado distribuido ni rendimiento. Esos criterios permanecen en el inventario vigente hasta tener evidencia.
