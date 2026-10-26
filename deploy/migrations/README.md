# Migraciones PostgreSQL

El esquema vigente es `deploy/schema.sql` **seguido de** las migraciones SQL numeradas de esta carpeta, en orden. La definición de la outbox vive solo en `001-block-delete-outbox.sql`: no se mantiene otra copia en el esquema base.

## Instalación nueva

Compose monta el esquema como `01-schema.sql`, la migración 001 como `01a-delete-outbox.sql`
y la 002 como `01b-file-versions.sql`, antes de configurar la replicación. PostgreSQL los
ejecuta solamente al inicializar un volumen vacío. La 002 añade la relación entre versiones,
el token del lease y los índices que permiten staging junto a la versión confirmada.

Fuera de Compose, aplicar `schema.sql` una vez y luego la migración con `psql -v ON_ERROR_STOP=1 --single-transaction -f ...` sobre la base elegida.

## Base existente: conservar sus datos

No usar `docker compose down -v`: elimina los volúmenes. Hacer un respaldo verificado y coordinar una ventana sin operaciones de archivo (detener ambos ControlNodes y clientes). El DataNode y RabbitMQ pueden conservar sus volúmenes.

Desde `deploy/`, con el primario aún llamado `postgres` y usando Git Bash:

```bash
docker compose stop controlnode1 controlnode2
docker compose exec -T postgres psql -U dfsha -d dfsha -v ON_ERROR_STOP=1 --single-transaction < migrations/001-block-delete-outbox.sql
docker compose exec -T postgres psql -U dfsha -d dfsha -v ON_ERROR_STOP=1 --single-transaction < migrations/002-file-versions.sql
# Solo si psql terminó correctamente:
docker compose up -d --build controlnode1 controlnode2
```

Si ya hubo promoción, ejecutar contra el **primario actual**, no contra el antiguo primario ni un standby. No automatizar una promoción para aplicar esta migración. El cambio se replica por streaming al standby: verificar que esté al día antes de considerar un failover.

La migración es reejecutable: conserva usuarios, archivos, ubicaciones, contadores e intentos; recupera tareas para copias ya marcadas `DELETED` por versiones anteriores. No reabre publicaciones ya confirmadas. La recuperación puede repetir un borrado ya enviado: los IDs de bloque son únicos y `BlockStore.delete` usa `deleteIfExists`.

El ControlNode nuevo comprueba la tabla de outbox y las columnas de versiones antes de abrir
su RPC. No aplica DDL automáticamente ni puede funcionar contra una base sin migrar.
La 002 es reejecutable, pero reconstruye un índice: mantener la ventana sin escritores.
No arrancar binarios anteriores tras confirmar nuevas versiones: entienden el índice antiguo.
Para rollback, detener clientes/ControlNodes y restaurar respaldo/esquema compatibles mediante
un plan revisado; no eliminar columnas ni versiones a ciegas.

## Operación y límites

- `rm` confirma el borrado **lógico** y las tareas, no la limpieza física.
- `DeleteOutbox` toma filas vencidas con `FOR UPDATE SKIP LOCKED`, publica con `mandatory` y publisher confirms, y registra `published_at` solo al terminar el envío confirmado. Mantiene el bloqueo de una fila durante el envío (confirmación de hasta 5 s); no bloquea todo el lote ni el namespace.
- Un fallo conserva la tarea y pospone el intento `DELETE_RETRY_SECONDS` (10 por defecto). Cada trabajador revisa cada 2 s y procesa hasta 100 éxitos por vuelta; ante el primer fallo espera a la siguiente vuelta. Al reiniciar retoma el estado persistido. Un canal AMQP nuevo por tarea evita reutilizar un canal fallido o confundir confirmaciones.
- Puede haber entrega repetida si el broker aceptó y luego se perdió su confirmación, falló SQL o murió el proceso. La garantía es **al menos una vez**, no exactamente una.
- `published_at` significa aceptada/enrutada por RabbitMQ, **no eliminada del disco**. Un fallo del consumidor va a `dfsha.delete.failed`; la recuperación automática de esa DLQ y el acuse de borrado físico no forman parte de esta corrección.
- `BlockStore.delete` persiste `<uuid>.deleted` antes de retirar el bloque. Un reenvío o réplica
  tardíos de ese UUID se rechazan incluso tras reiniciar. Un reenvío idéntico de un bloque vivo
  es idempotente; otro contenido con el mismo UUID se rechaza. Solo un proceso debe escribir
  en cada volumen. No borrar tombstones manualmente: se conservan para impedir resurrección;
  no hay política de GC certificada ni tolerancia a pérdida física del volumen.
- `DeleteOutbox` revisa cargas PENDING abandonadas cada 30 s, en lotes de hasta 100, y persiste
  sus tareas de borrado. `PENDING_UPLOAD_TTL_SECONDS` vale 3600 por defecto (mínimo 60).
  No hay heartbeat de carga: ajustar ese TTL antes de permitir subidas de más de una hora.
  Se limpian también reemplazos pendientes; nunca se publica una versión incompleta.
- La DLQ `dfsha.delete.failed` requiere intervención: revisar error, restaurar conectividad/disco,
  identificar UUID/nodo y reencolar solo tareas válidas con confirmación; después comprobar
  ausencia física. No purgar la cola ni declarar éxito por `published_at`. No se incluye un
  consumidor automático de DLQ ni se certifica limpieza de todos los huérfanos históricos.
- Se conservan las filas publicadas para diagnóstico. Acordar retención antes de una operación prolongada. La durabilidad depende también de PostgreSQL y del broker: no elimina las limitaciones de HA documentadas.

Consulta de seguimiento (en la base del proyecto):

```sql
SELECT block_id, datanode_id, attempts, next_attempt_at, last_error
FROM block_delete_outbox
WHERE published_at IS NULL
ORDER BY next_attempt_at;
```
