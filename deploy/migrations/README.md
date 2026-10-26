# Migraciones PostgreSQL

El esquema vigente es `deploy/schema.sql` **seguido de** las migraciones SQL numeradas de esta carpeta, en orden. La definición de la outbox vive solo en `001-block-delete-outbox.sql`: no se mantiene otra copia en el esquema base.

## Instalación nueva

Compose monta el esquema como `01-schema.sql` y la primera migración como `01a-delete-outbox.sql`, antes de configurar la replicación. PostgreSQL los ejecuta solamente al inicializar un volumen vacío.

Fuera de Compose, aplicar `schema.sql` una vez y luego la migración con `psql -v ON_ERROR_STOP=1 --single-transaction -f ...` sobre la base elegida.

## Base existente: conservar sus datos

No usar `docker compose down -v`: elimina los volúmenes. Hacer un respaldo verificado y coordinar una ventana sin operaciones de archivo (detener ambos ControlNodes y clientes). El DataNode y RabbitMQ pueden conservar sus volúmenes.

Desde `deploy/`, con el primario aún llamado `postgres` y usando Git Bash:

```bash
docker compose stop controlnode1 controlnode2
docker compose exec -T postgres psql -U dfsha -d dfsha -v ON_ERROR_STOP=1 --single-transaction < migrations/001-block-delete-outbox.sql
# Solo si psql terminó correctamente:
docker compose up -d --build controlnode1 controlnode2
```

Si ya hubo promoción, ejecutar contra el **primario actual**, no contra el antiguo primario ni un standby. No automatizar una promoción para aplicar esta migración. El cambio se replica por streaming al standby: verificar que esté al día antes de considerar un failover.

La migración es reejecutable: conserva usuarios, archivos, ubicaciones, contadores e intentos; recupera tareas para copias ya marcadas `DELETED` por versiones anteriores. No reabre publicaciones ya confirmadas. La recuperación puede repetir un borrado ya enviado: los IDs de bloque son únicos y `BlockStore.delete` usa `deleteIfExists`.

El ControlNode nuevo comprueba la tabla antes de abrir su RPC. No aplica DDL automáticamente ni puede funcionar contra una base sin migrar.

## Operación y límites

- `rm` confirma el borrado **lógico** y las tareas, no la limpieza física.
- `DeleteOutbox` toma filas vencidas con `FOR UPDATE SKIP LOCKED`, publica con `mandatory` y publisher confirms, y registra `published_at` solo al terminar el envío confirmado. Mantiene el bloqueo de una fila durante el envío (confirmación de hasta 5 s); no bloquea todo el lote ni el namespace.
- Un fallo conserva la tarea y pospone el intento `DELETE_RETRY_SECONDS` (10 por defecto). Cada trabajador revisa cada 2 s y procesa hasta 100 éxitos por vuelta; ante el primer fallo espera a la siguiente vuelta. Al reiniciar retoma el estado persistido. Un canal AMQP nuevo por tarea evita reutilizar un canal fallido o confundir confirmaciones.
- Puede haber entrega repetida si el broker aceptó y luego se perdió su confirmación, falló SQL o murió el proceso. La garantía es **al menos una vez**, no exactamente una.
- `published_at` significa aceptada/enrutada por RabbitMQ, **no eliminada del disco**. Un fallo del consumidor va a `dfsha.delete.failed`; la recuperación automática de esa DLQ y el acuse de borrado físico no forman parte de esta corrección.
- Tampoco corrige escrituras/réplicas en vuelo que lleguen después del borrado, vencimiento de cargas PENDING ni limpieza de todos los huérfanos. Requieren validación y trabajo posterior.
- Se conservan las filas publicadas para diagnóstico. Acordar retención antes de una operación prolongada. La durabilidad depende también de PostgreSQL y del broker: no elimina las limitaciones de HA documentadas.

Consulta de seguimiento (en la base del proyecto):

```sql
SELECT block_id, datanode_id, attempts, next_attempt_at, last_error
FROM block_delete_outbox
WHERE published_at IS NULL
ORDER BY next_attempt_at;
```
