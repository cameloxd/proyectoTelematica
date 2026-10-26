# Validación de C2.1/C2.2 con PostgreSQL

Base de estas pruebas: `develop` en `8ed725c` (PR #9). No se reutilizan las clases ni el esquema de la implementación anterior.

## Qué prueban

`ControlFileFlowPostgresTest` levanta el ControlService y el NodeRegistryService reales en un puerto TCP de loopback y utiliza PostgreSQL real. Cada caso carga `deploy/schema.sql` en un esquema aleatorio `dfsha_c2_<uuid>`, que elimina al terminar. No trunca tablas de otros esquemas.

Cobertura:

- Partición de 20 MiB en 8 + 8 + 4 MiB, offsets, rondas en tres nodos y tokens de escritura.
- Límites de bloque y archivos vacíos.
- Exclusión de nodos DEAD, SUSPECT o saturados.
- Rollback completo por falta de nodos o espacio, incluso tras asignar un primer bloque.
- Reintentos secuenciales y concurrentes; rechazo de reutilización de operation_id con otros parámetros.
- PENDING invisible, identificación de bloques faltantes y commit repetido.
- Manifiesto ordenado con checksums, ubicaciones y tokens de lectura.
- Reportes repetidos, tamaño/checksum incorrectos, nodo equivocado y token inválido.
- Aislamiento entre usuarios y registro repetido del DataNode.
- Cancelación de una subida PENDING, reportes tardíos y colisión archivo/directorio.
- Dos asignaciones simultáneas compitiendo por el mismo espacio.

El test concurrente de operation_id usa el mismo advisory lock de producción como barrera y verifica en `pg_locks` que las dos peticiones estén en espera antes de liberarlo. La comprobación de idempotencia debe ejecutarse después de adquirir ese lock. Las pruebas de mkdir frente a AllocateFile retienen la fila del usuario y comprueban en `pg_stat_activity` que ambos escritores estén en vuelo; cubren ambos órdenes de llegada. No dependen solamente de lanzar dos hilos a la vez.

## Ejecutar

Usar una base de pruebas cuyo usuario tenga permiso CREATE SCHEMA. No se necesita RabbitMQ para estos RPC.

```powershell
$env:DFSHA_TEST_DB_URL = 'jdbc:postgresql://127.0.0.1:55432/postgres'
$env:DFSHA_TEST_DB_USER = 'c14'
$env:DFSHA_TEST_DB_PASSWORD = ''
$env:BLOCK_SIZE_BYTES = '8388608'
$env:RESERVED_BYTES = '0'
$env:MAX_TRANSFERS_PER_NODE = '8'
$env:TARGET_BLOCKS_PER_NODE = '1000'
mvn -pl dfsha-common,dfsha-controlnode -am test
```

Sin `DFSHA_TEST_DB_URL`, JUnit omite intencionalmente las pruebas PostgreSQL. Una compilación verde con esos casos omitidos **no** demuestra validación de C2.1/C2.2.

Las claves Ed25519 y los usuarios son temporales. Los DataNodes se registran mediante RPC, pero los reportes de almacenamiento se envían desde el test: no se transfieren físicamente 20 MiB a tres discos. Esta batería no demuestra cifrado, RabbitMQ, TLS, réplica física ni failover de PostgreSQL.

## Resultado de la validación (2026-10-25)

La ejecución inicial desde la terminal de Camilo compiló correctamente el contrato v1.1, common y controlnode. Ejecutó 39 casos: 36 aprobados y tres fallos de regresión reproducidos:

- `concurrentSameOperationMustReturnTheSamePlanToBothClients`: una petición concurrente recibió ALREADY_EXISTS en lugar del mismo plan.
- `retryOfSupersededOperationMustNotReturnADeletedUploadPlan`: se devolvió un plan marcado DELETED.
- `pendingFileAndDirectoryCannotOccupyTheSamePath`: se permitió crear un directorio sobre la ruta de un archivo PENDING.

Después de corregirlos se completaron dos ejecuciones satisfactorias: la primera con 43 casos y la segunda con la batería final de **44 casos, cero fallos, cero errores y cero omitidos**. La batería final incluye 27 casos de PostgreSQL/gRPC, seis unitarios de controlnode y once de common. Los casos repetidos entre ejecuciones no se cuentan como pruebas distintas.

Las dos comprobaciones posteriores reutilizaron las clases del contrato ya generadas en la terminal de Camilo: el ejecutable nativo de protoc no funciona en el entorno automatizado. Antes de omitir exclusivamente la generación se verificaron el hash del .proto fuente frente al recurso compilado y la existencia/fecha de sus clases. No hubo cambios al contrato. El ControlNode corregido y las nuevas pruebas sí se compilaron con release 17 (JDK 24).

PostgreSQL temporal se detuvo correctamente al terminar cada ejecución. No se probaron transferencias físicas a DataNodes ni los componentes excluidos en la sección anterior.

## Correcciones aplicadas

- El advisory lock global de AllocateFile ya existía para proteger la capacidad: ahora se adquiere al inicio de la transacción, antes de consultar operation_id, reemplazar una subida o reservar espacio. Un reintento concurrente observa el plan confirmado por la primera petición.
- La consulta de idempotencia incluye el estado del archivo. Si es DELETED, devuelve FAILED_PRECONDITION y exige otro operation_id; no se firma de nuevo el plan obsoleto. Los reintentos PENDING y COMMITTED siguen funcionando.
- CreateDirectory comprueba archivos PENDING y COMMITTED. mkdir, rm/rmdir y AllocateFile comparten un bloqueo de la fila del usuario dentro de la transacción; no queda una ventana entre comprobar la ruta e insertarla. Distintos usuarios no comparten ese bloqueo de namespace, aunque las asignaciones siguen compartiendo el lock global de capacidad.
- El constructor de Db tiene acceso de paquete para inyectar el pool aislado en los tests; su lógica de commit/rollback no cambia.

Las nuevas regresiones adicionales comprueban colisiones simultáneas en ambos órdenes, capacidad compartida entre dos usuarios, que un reemplazo fallido restaure el plan original, el reintento tras COMMITTED y que una ruta borrada pueda reutilizarse sin reactivar la operación vieja.

Las pruebas no se ignoraron ni se alteraron para aceptar los comportamientos defectuosos. Los cambios se mantienen en la rama local `test/control-phase2-current`; no se modificó develop ni se publicó un PR.
