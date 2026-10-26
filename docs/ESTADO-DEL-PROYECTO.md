# Estado vigente de DFSha

Actualización: 2026-10-25. Alcance: consolidación previa al despliegue, en la rama local `chore/consolidate-predeploy`.

La base integrada del equipo es el PR #9 (`develop`, `8ed725c`). Esta rama incorpora también las correcciones locales de `18a9fe2`; aún requiere publicación, revisión e integración. No debe presentarse como si esos cambios ya estuvieran en GitHub.

## Regla de trabajo: una capacidad, una implementación

Antes de crear una tarea, revisar este inventario y la [guía del código](GUIA-DEL-CODIGO.md). Si una capacidad existe, corregirla, probarla o documentarla en su módulo actual: no crear otra versión por integrante o fase.

- La CLI solo traduce comandos; las operaciones del cliente viven en `DfsClient`.
- Los metadatos y RPC de usuario viven en `ControlServiceImpl`; el registro/reporte de nodos, en `NodeRegistryImpl`.
- La selección de destinos para primarias o nuevas réplicas utiliza `Placement`. Toda reserva primaria o de réplica usa el mismo candado `CapacityReservations`.
- El escaneo y planificación de réplicas viven en `ClusterMonitor`; la copia física vive en `Replicator`.
- El almacenamiento y cifrado viven en `BlockStore`; `DataNodeServiceImpl` implementa el protocolo de bloques.
- Tokens, gRPC y RabbitMQ se reutilizan desde `dfsha-common`.
- `rm` y `rmdir` son comandos distintos que reutilizan el mismo RPC: no son implementaciones duplicadas. Cliente y replicador también reutilizan `ReadBlock` deliberadamente.

Las ramas antiguas de C1.4 y de la primera integración de Mendel son referencias históricas, no funcionalidades que deban volver a mezclarse completas. Antes de reutilizar un cambio aislado, compararlo con el `develop` actual. No se borran ni se fuerzan esas ramas como parte de la limpieza.

## Responsabilidades de mantenimiento

La responsabilidad es de revisión y explicación, no de autoría exclusiva de los commits. Cambiar un módulo existente no significa apropiarse del trabajo de otro integrante.

- Camilo: esquema de metadatos, `Tokens`, `Db`, `ControlServiceImpl`, `NodeRegistryImpl`, `Placement`, `CapacityReservations` y planificación de réplicas de `ClusterMonitor`.
- Mendel: `ClientMain`, `DfsClient`, `BlockStore`, `DataNodeServiceImpl`, `Replicator` y ensamblaje de `DataNodeMain`.
- Martín: contrato proto, utilidades gRPC/RabbitMQ, `ControlLink`, heartbeats/estados de `ClusterMonitor` e infraestructura.

`ClusterMonitor` es compartido por secciones; coordinar su modificación. Un cambio al contrato necesita acuerdo con Martín y adaptación de los consumidores, no un contrato paralelo.

## Ya implementado: no volver a empezar

### Camilo

- C1: persistencia PostgreSQL, registro de nodos, login y namespace. El diseño actual usa PBKDF2 y tokens Ed25519; no duplicar la autenticación con la implementación BCrypt anterior.
- C2.1/C2.2: asignación por bloques, rondas y puntuación, reservas, idempotencia, reportes, confirmación y manifiestos. Las tres regresiones de concurrencia/namespace detectadas están corregidas en la base local.
- C2.3: borrado lógico y publicación RabbitMQ implementados; su recuperación ante fallos del broker sigue pendiente.
- Fase 3: colocación sin repetir nodo y factor de replicación configurable. Las reservas de réplicas ahora comparten el candado de las primarias. Shards/líderes eran una evaluación propuesta: documentar la decisión antes de añadir otra arquitectura.

### Mendel

- Fase 1: CLI, bloques, SHA-256, FileChannel y DataNode. El fake anterior fue retirado al conectar servicios reales; no hace falta restaurarlo como una segunda aplicación.
- Fase 2: llamadas reales al ControlNode, put/get paralelos, reintentos, manejo de bloques faltantes y tokens del DataNode.
- Fase 3: lectura desde réplicas, failover, TLS configurable y cifrado AES-GCM. Las pruebas de carga y las pruebas automáticas del cliente siguen pendientes.

### Martín e integración

- Contrato v1.1, Bearer, canales gRPC, RabbitMQ, registro al arrancar, heartbeats y estados de nodos.
- Escaneo de sub-replicación, tareas de copia, Compose con dos ControlNodes, cuatro DataNodes y PostgreSQL primario/standby.
- Scripts de secretos, promoción manual y siete escenarios. Su existencia no demuestra que se hayan ejecutado satisfactoriamente.

## Evidencia de esta consolidación

La batería completa terminó con **51 pruebas aprobadas, cero fallos, cero errores y cero omitidas**:

- common: 11 pruebas.
- controlnode: 35 pruebas, de las cuales 29 usan PostgreSQL real (27 de flujo de archivos y dos de planificación de réplicas).
- datanode: cinco pruebas de almacenamiento/cifrado, incluida la validación del UUID antes de crear temporales.
- client compila, pero todavía no tiene pruebas propias.

La segunda pasada, con la fase Maven `package` y la misma batería completa, también terminó en BUILD SUCCESS y generó los tres jars ejecutables de cliente, ControlNode y DataNode. Empaquetar no inició los servicios.

Las dos pruebas nuevas del monitor comprueban reservas en nodos distintos, que otro escaneo no duplique el plan y que el escáner espere una reserva primaria antes de volver a calcular capacidad. No copian bloques por RabbitMQ ni demuestran replicación física.

Se usó JDK 24 con `release 17`. En el entorno automatizado se reutilizaron las clases del contrato que Camilo ya había generado; antes se verificaron hash del proto y vigencia de las clases. No hubo cambios al contrato ni se afirma una generación limpia con protoc en ese entorno. Los temporales de almacenamiento se aislaron en el workspace; PostgreSQL de pruebas se detuvo al terminar.

Comandos y límites: [PRUEBAS.md](PRUEBAS.md). El registro anterior de 44 pruebas solo cubría common/ControlNode; no era una validación de todos los módulos.

## Pendientes reales antes de cerrar el despliegue

No confundir esta limpieza con una certificación de producción:

- Probar el cliente actual: put/get de 20 MiB con SHA-256 idéntico, tres DataNodes y fallos durante transferencias.
- Ejecutar y conservar resultados de los siete escenarios en un clúster de pruebas autorizado; todavía no se ejecutaron en esta consolidación.
- Cerrar la ventana entre commit de borrado y publicación RabbitMQ: no hay outbox ni recuperación completa de borrados fallidos.
- Proteger la inmutabilidad ante reenvíos con datos distintos: `BlockStore.save` todavía puede reemplazar un bloque antes de que el ControlNode rechace el checksum.
- Limpiar PENDING abandonados y las copias físicas de subidas reemplazadas.
- Acordar RF3: existen read/cat y lock/unlock, pero no escritura parcial ni handles open/close de archivos. WORM es el alcance actual del cliente, no una garantía de inmutabilidad ya probada en todos los reintentos.
- Documentar los límites de HA: promoción PostgreSQL manual, streaming asíncrono, RabbitMQ de un nodo, sin TLS AMQP/SQL configurado. No garantizar pérdida cero tras promover un standby.
- Medir carga, revisar trazabilidad JSON/request_id y auditoría pendientes respecto al diseño anterior, y acordar decommission/rebalanceo.
- Preparar evidencia de nube, informe y video; publicar/revisar las ramas antes de actualizar `develop`, y cerrar `main`/tags solo con aceptación del equipo.

El siguiente paso no es reimplementar fases, sino revisar e integrar esta consolidación y preparar esas validaciones.
