# Estado vigente de DFSha

Actualización: 2026-10-26, America/Bogota. Rama de entrega: `feat/local-release-ready`.
Este inventario distingue implementación, validación local y publicación: no es un certificado
de producción ni una afirmación de que la rama ya esté integrada.

## Integración y revisión

- PR #10 (`cb67cf0`): consolidación previa.
- PR #11 (`7e098f4`): outbox y demo básica integradas a `develop`.
- `08a9a61`: RF3 por versiones, inmutabilidad y limpieza de cargas pendientes.
- `74c560b`: overlay TLS SQL/AMQP y demo aislada con carga/fallos; commit del recibo de ejecución.
- Camilo confirmó el 26 de octubre la aprobación de esta nueva versión por Martín y el equipo.
  La confirmación en conversación no equivale a una review registrada en GitHub.
- La alineación `develop` -> `main` se hace mediante PR/merge normal, conservando ambas historias.
  Verificar las referencias remotas y el diff final; no usar reset forzado ni force-push.

## Una capacidad, una implementación

La CLI traduce comandos a `DfsClient`. `ControlServiceImpl` mantiene los RPC de usuarios;
`NodeRegistryImpl`, los reportes de nodos. `Placement` y `CapacityReservations` se reutilizan
en primarias y réplicas. `ClusterMonitor` planifica; `Replicator` copia; `BlockStore` cifra,
conserva inmutabilidad y tombstones. `DeleteOutbox` publica las tareas persistidas.
No abrir implementaciones paralelas por fase o por integrante.

## Responsables de explicación y mantenimiento

- Camilo: esquema/migraciones, Tokens, Db, ControlServiceImpl, NodeRegistryImpl, Placement,
  CapacityReservations, DeleteOutbox y planificación de réplicas.
- Mendel: ClientMain, DfsClient, BlockStore, DataNodeServiceImpl, Replicator y DataNodeMain.
- Martín: proto, gRPC/RabbitMQ, ControlLink, heartbeats/estados de ClusterMonitor e infraestructura.

Las responsabilidades no son autoría exclusiva. RF3 cruza cliente, contrato y ControlNode;
modificar estas partes requiere revisión compartida.

## Implementación actual

- C1: PostgreSQL, registro de nodos, login PBKDF2 y namespace por propietario.
- C2: partición, asignación, reservas, idempotencia, reportes, commit y manifiestos.
- C2.3: borrado lógico + outbox en una transacción; publicación reintentable al menos una vez.
- Fase 3: replicación eventual, detección ALIVE/SUSPECT/DEAD, reposición y lecturas con failover.
- Cliente/DataNode: put/get paralelos, checksum, FileChannel, tokens y AES-GCM.
- RF3: instantáneas abiertas, read/write por offset en bytes, lease exclusivo y reemplazo atómico
  al cerrar. No se modifica el bloque físico existente; cada versión utiliza UUID nuevos.
- Cargas PENDING abandonadas: expiración periódica con tareas de borrado persistidas.
- TLS de gRPC y, con overlay seguro, de AMQP/JDBC/replicación PostgreSQL con CA/hostname verificados.

## Evidencia Maven: 89 pruebas

El 26 de octubre la fase `package` terminó con cero fallos, errores y omisiones:

- common: 17 (rutas 2, publicación de borrados 4, configuración TLS AMQP 2, RPC 4, tokens 5).
- controlnode: 55 (flujo PostgreSQL 27, versiones PostgreSQL 7, outbox PostgreSQL 13,
  monitor PostgreSQL 2, contraseñas 2, colocación 4).
- datanode: 11 de almacenamiento/cifrado, reenvío idéntico/distinto, tombstones y carreras.
- client: 6 del ciclo de archivos abiertos con gRPC local y protocolo de servidor acotado.

Hay 49 casos con PostgreSQL real. Los tests del cliente no son un clúster real; AMQP simulado
no demuestra RabbitMQ por sí solo. Se usó JDK 24/release 17, contrato v1.2 generado desde la
PowerShell de Camilo y compilación con fuentes generadas verificadas; no se afirma un `clean`
en el sandbox Windows. La generación nativa terminó BUILD SUCCESS. Ver [PRUEBAS.md](PRUEBAS.md).

## Evidencia distribuida local: aprobada

Proyecto `dfsha-demo-entrega-20261026`, resultado del 2026-10-26 01:16:13 -05:00,
commit `74c560bccd3976be370954c4250008e06a46d917`. Recibo y logs sanitizados en
[entrega/evidencia](entrega/evidencia/resultado.json).

- Cuatro DataNodes ALIVE; put/get de 20 MiB en tres bloques con al menos tres copias vivas.
- SHA-256 original/descarga: `D9BD7967570F3075F0ED345833B31C7436D3ACFD1F5109B232F0F5F6F809B247`.
- RF3: escritor cambia a LISTO, lector ya abierto conserva DFSHA y nueva apertura ve la versión confirmada.
- Borrado: outbox publicada y UUID ausentes de los cuatro discos.
- Seguridad: SQL TLS verificado/plaintext rechazado, AMQP solo TLS, gRPC sin TLS rechazado,
  aislamiento de usuarios y exclusión/expiración de locks.
- Carga: cuatro clientes concurrentes de 20 MiB, cuatro checksums correctos; 160 MiB de
  transferencia total en 47,868 s, 3,343 MiB/s agregados. Incluye login y borrado, no es ancho de banda puro.
- Caída de un DataNode: lectura desde otra copia, detección DEAD y reposición del factor tres.
- Caída de un ControlNode: lectura correcta usando el otro endpoint.
- Caída del broker: borrado lógico, tarea pendiente observada y limpieza física tras recuperarlo.
- PostgreSQL: WAL sincronizado antes de detener el primario, promoción manual y lectura correcta.

El standby de ESTE proyecto quedó promovido. NO reiniciar el primario antiguo ni ejecutar `up`
en ese proyecto. La demo básica del 25 de octubre es evidencia histórica distinta, no se sobrescribe.

## Límites y cierre de entrega

- El commit inicial no espera las tres réplicas: existe ventana de sub-replicación.
- No hay HA del host ni clúster RabbitMQ; el broker vuelve a operar al recuperarse, no durante su caída.
- Streaming PostgreSQL asíncrono: la prueba sincronizó WAL, no demuestra pérdida cero ante un fallo abrupto.
- RF3 es una API con caché de archivo completo y lease de 600 s, sin renovación automática;
  no es POSIX/FUSE ni escritura remota in-place. `exit` descarta handles sin cerrar.
- DLQ requiere recuperación operativa; publicación confirmada no es acuse de borrado físico.
- Tombstones se conservan; no hay GC certificado, rebalanceo ni decommission.
- Clave AES compartida y CA local de demo: no es gestión de claves/certificados de producción.
- AWS/GCP e Internet no se validaron, por exclusión expresa de Camilo. Siguen siendo exigencias
  de la asignación, no una excepción confirmada por el profesor.
- Informe y guion en `entrega/`. Un guion NO sustituye el video final de 10-15 minutos;
  grabarlo, revisar su duración y adjuntarlo a la entrega.
- Cerrar PR a develop, PR de entrega a main y tag solo tras comprobar sus resultados remotos.
