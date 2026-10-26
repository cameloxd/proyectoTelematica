# Estado vigente de DFSha

Actualización: 2026-10-25, 23:55 America/Bogota. Alcance: outbox de borrados y demostración local básica en `chore/local-demo`.

La base integrada es el PR #10 (`develop`, `cb67cf0`), que consolida las cuatro correcciones/documentación sobre el PR #9. La outbox posterior está publicada en `fix/control-delete-outbox` (`ccd6966`) y aún requiere PR/revisión/integración propios. La preparación y correcciones de la demo viven en `chore/local-demo`. No confundir las ramas con los cambios ya integrados a develop.

## Regla de trabajo: una capacidad, una implementación

Antes de crear una tarea, revisar este inventario y la [guía del código](GUIA-DEL-CODIGO.md). Si una capacidad existe, corregirla, probarla o documentarla en su módulo actual: no crear otra versión por integrante o fase.

- La CLI solo traduce comandos; las operaciones del cliente viven en `DfsClient`.
- Los metadatos y RPC de usuario viven en `ControlServiceImpl`; el registro/reporte de nodos, en `NodeRegistryImpl`.
- La selección de destinos para primarias o nuevas réplicas utiliza `Placement`. Toda reserva primaria o de réplica usa el mismo candado `CapacityReservations`.
- El escaneo y planificación de réplicas viven en `ClusterMonitor`; la copia física vive en `Replicator`.
- El borrado lógico y las tareas se confirman juntos; `DeleteOutbox` reintenta su publicación usando `Rabbit.publishDelete`. No hay un segundo servicio de borrado ni cambios al contrato proto.
- El almacenamiento y cifrado viven en `BlockStore`; `DataNodeServiceImpl` implementa el protocolo de bloques.
- Tokens, gRPC y RabbitMQ se reutilizan desde `dfsha-common`.
- `rm` y `rmdir` son comandos distintos que reutilizan el mismo RPC: no son implementaciones duplicadas. Cliente y replicador también reutilizan `ReadBlock` deliberadamente.

Las ramas antiguas de C1.4 y de la primera integración de Mendel son referencias históricas, no funcionalidades que deban volver a mezclarse completas. Antes de reutilizar un cambio aislado, compararlo con el `develop` actual. No se borran ni se fuerzan esas ramas como parte de la limpieza.

## Responsabilidades de mantenimiento

La responsabilidad es de revisión y explicación, no de autoría exclusiva de los commits. Cambiar un módulo existente no significa apropiarse del trabajo de otro integrante.

- Camilo: esquema/migraciones de metadatos, `Tokens`, `Db`, `ControlServiceImpl`, `NodeRegistryImpl`, `Placement`, `CapacityReservations`, `DeleteOutbox` y planificación de réplicas de `ClusterMonitor`.
- Mendel: `ClientMain`, `DfsClient`, `BlockStore`, `DataNodeServiceImpl`, `Replicator` y ensamblaje de `DataNodeMain`.
- Martín: contrato proto, utilidades gRPC/RabbitMQ, `ControlLink`, heartbeats/estados de `ClusterMonitor` e infraestructura.

`ClusterMonitor` es compartido por secciones; coordinar su modificación. Un cambio al contrato necesita acuerdo con Martín y adaptación de los consumidores, no un contrato paralelo.

## Ya implementado: no volver a empezar

### Camilo

- C1: persistencia PostgreSQL, registro de nodos, login y namespace. El diseño actual usa PBKDF2 y tokens Ed25519; no duplicar la autenticación con la implementación BCrypt anterior.
- C2.1/C2.2: asignación por bloques, rondas y puntuación, reservas, idempotencia, reportes, confirmación y manifiestos. Las regresiones de concurrencia/namespace están corregidas e integradas por el PR #10.
- C2.3: borrado lógico y tareas en una transacción PostgreSQL, con publicación diferida/reintentable por outbox en esta rama. La migración recupera también copias borradas por versiones anteriores. Falta demostrar la caída/recuperación con RabbitMQ real, el consumidor/DLQ y las escrituras tardías; no afirmar eliminación física garantizada.
- Fase 3: colocación sin repetir nodo y factor de replicación configurable. Las reservas de réplicas ahora comparten el candado de las primarias. Shards/líderes eran una evaluación propuesta: documentar la decisión antes de añadir otra arquitectura.

### Mendel

- Fase 1: CLI, bloques, SHA-256, FileChannel y DataNode. El fake anterior fue retirado al conectar servicios reales; no hace falta restaurarlo como una segunda aplicación.
- Fase 2: llamadas reales al ControlNode, put/get paralelos, reintentos, manejo de bloques faltantes y tokens del DataNode.
- Fase 3: lectura desde réplicas, failover, TLS configurable y cifrado AES-GCM. Las pruebas de carga y las pruebas automáticas del cliente siguen pendientes.

### Martín e integración

- Contrato v1.1, Bearer, canales gRPC, RabbitMQ, registro al arrancar, heartbeats y estados de nodos.
- Escaneo de sub-replicación, tareas de copia, Compose con dos ControlNodes, cuatro DataNodes y PostgreSQL primario/standby.
- Scripts de secretos, promoción manual y siete escenarios. Su existencia no demuestra que se hayan ejecutado satisfactoriamente.

## Evidencia de la consolidación integrada (PR #10)

La batería completa terminó con **51 pruebas aprobadas, cero fallos, cero errores y cero omitidas**:

- common: 11 pruebas.
- controlnode: 35 pruebas, de las cuales 29 usan PostgreSQL real (27 de flujo de archivos y dos de planificación de réplicas).
- datanode: cinco pruebas de almacenamiento/cifrado, incluida la validación del UUID antes de crear temporales.
- client compila, pero todavía no tiene pruebas propias.

La segunda pasada, con la fase Maven `package` y la misma batería completa, también terminó en BUILD SUCCESS y generó los tres jars ejecutables de cliente, ControlNode y DataNode. Empaquetar no inició los servicios.

Las dos pruebas nuevas del monitor comprueban reservas en nodos distintos, que otro escaneo no duplique el plan y que el escáner espere una reserva primaria antes de volver a calcular capacidad. No copian bloques por RabbitMQ ni demuestran replicación física.

Se usó JDK 24 con `release 17`. En el entorno automatizado se reutilizaron las clases del contrato que Camilo ya había generado; antes se verificaron hash del proto y vigencia de las clases. No hubo cambios al contrato ni se afirma una generación limpia con protoc en ese entorno. Los temporales de almacenamiento se aislaron en el workspace; PostgreSQL de pruebas se detuvo al terminar.

Comandos y límites: [PRUEBAS.md](PRUEBAS.md). El registro anterior de 44 pruebas solo cubría common/ControlNode; no era una validación de todos los módulos.

## Evidencia local de borrados recuperables (posterior al PR #10)

En `fix/control-delete-outbox`, la pasada final de Maven `package` aprobó **69 pruebas, cero fallos, cero errores y cero omitidas**: common 15, controlnode 48 y datanode 6. Son las 51 pruebas de base más 18 nuevas, no la suma de ejecuciones repetidas. Los 42 casos PostgreSQL son 27 de flujo, dos del monitor y 13 de la outbox. El cliente compila sin pruebas propias.

Se verificó la transacción de borrado/tarea, rollback SQL, transporte fallido con espera persistida, recuperación mediante otra instancia del trabajador, reparto con filas bloqueadas, recuperación de borrados antiguos y reejecución segura de la migración. Un fallo SQL simulado después de la confirmación del transporte verifica que se puede reenviar, en lugar de perder una tarea. Otro caso inicia y detiene el trabajador programado; no equivale a matar/reiniciar procesos ControlNode reales.

Los cuatro casos AMQP de esa batería usan un canal simulado (retorno sin ruta, timeout, nack y envío confirmado). El caso nuevo de almacenamiento comprueba un borrado duplicado con dos UUID distintos. En esa pasada Maven no se usó un broker real ni se ejecutó una eliminación distribuida; posteriormente sí se ejecutó la demo básica real descrita abajo.

Se conserva el mismo límite de entorno: JDK 24/release 17 y contrato previamente generado/verificado, sin `clean` ni cambios al proto. Se generaron los tres jars y se detuvo el PostgreSQL temporal. La migración se ejecutó únicamente en esquemas aislados de prueba; no se aplicó a una base existente del proyecto. Hay reportes antiguos en `target`: el recuento anterior solo incluye las clases ejecutadas en esta pasada, no archivos históricos.

## Demo básica local completada (23:55 America/Bogota)

En Docker Desktop, proyecto `dfsha-demo-20261025233922`, se registraron cuatro DataNodes y se ejecutaron put/get de 20 MiB con SHA-256 idéntico, lectura parcial, lock/unlock y borrado. Los 20 MiB se dividieron en tres bloques, cada uno con al menos tres copias WRITTEN/ALIVE. Se comprobó publicación de las tareas de borrado y ausencia física de esos bloques en los cuatro DataNodes. Esto usa RabbitMQ y PostgreSQL reales, pero no demuestra recuperación ante caída del broker ni otros fallos.

Se corrigió un CR final de los secretos generados en Windows: Java lo eliminaba y los scripts conservaban una contraseña distinta. Se sincronizaron únicamente los usuarios de aplicación del clúster existente; no se borraron volúmenes ni se promovió el standby. Detalle, hashes y ubicación del recibo: [DEMO-LOCAL.md](DEMO-LOCAL.md). El clúster quedó encendido. No hay despliegue en nube ni certificación completa.

## Pendientes reales antes de cerrar el despliegue

No confundir esta limpieza con una certificación de producción:

- Ampliar la demo aprobada del cliente con fallos durante transferencias y pruebas automáticas propias; el put/get básico y el factor tres ya fueron comprobados localmente.
- Ejecutar y conservar resultados de los siete escenarios en un clúster de pruebas autorizado; todavía no se ejecutaron en esta consolidación.
- Revisar/integrar esta outbox y comprobar una caída/recuperación real de RabbitMQ. Resolver por separado los fallos del consumidor/DLQ y las escrituras/réplicas en vuelo que lleguen después de un borrado. La outbox confirma entrega al broker, no eliminación física.
- Proteger la inmutabilidad ante reenvíos con datos distintos: `BlockStore.save` todavía puede reemplazar un bloque antes de que el ControlNode rechace el checksum.
- Limpiar PENDING abandonados y las copias físicas de subidas reemplazadas.
- Acordar RF3: existen read/cat y lock/unlock, pero no escritura parcial ni handles open/close de archivos. WORM es el alcance actual del cliente, no una garantía de inmutabilidad ya probada en todos los reintentos.
- Documentar los límites de HA: promoción PostgreSQL manual, streaming asíncrono, RabbitMQ de un nodo, sin TLS AMQP/SQL configurado. No garantizar pérdida cero tras promover un standby.
- Medir carga, revisar trazabilidad JSON/request_id y auditoría pendientes respecto al diseño anterior, y acordar decommission/rebalanceo.
- Preparar evidencia de nube, informe y video; publicar/revisar las ramas antes de actualizar `develop`, y cerrar `main`/tags solo con aceptación del equipo.

El siguiente paso es revisar e integrar la recuperación de publicación de borrados y preparar esas validaciones. Para una base existente se requiere la [migración incremental](../deploy/migrations/README.md): no borrar sus volúmenes.
