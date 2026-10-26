# Guion de sustentación y video - 12 minutos

Objetivo: grabar un video de 10-15 minutos con explicación y demostración. Este archivo
NO es el video. No se afirma que exista una grabación final. La asignación admite demo
en vivo o simulada; si se muestran logs de la pasada anterior, anunciarlo como reproducción
de una ejecución real, no como acciones en vivo.

Antes de grabar: ocultar contraseñas, claves, tokens, archivos .env y carpetas de secretos.
Preparar informe, código y logs sanitizados. No volver a levantar el proyecto de fallos:
su standby está promovido. No ejecutar escenarios.sh ni start postgres sobre él.
Si se desea una demo nueva en vivo, usar otro proyecto con el procedimiento de DEMO-LOCAL.md.

## 00:00-01:00 - Problema y objetivos (Camilo)

Presentar integrantes, curso y objetivo. Explicar que namespace/metadatos van a ControlNode,
pero bytes van directamente entre cliente y DataNodes. Anunciar alcance: demo local,
sin despliegue AWS/GCP ni validación de Internet; no atribuir excepción al profesor.
Mostrar portada y arquitectura del informe.

## 01:00-02:20 - Arquitectura y comunicaciones (Martín)

Mostrar módulos y diagrama. Explicar dos ControlNodes, PostgreSQL primario/standby,
cuatro DataNodes y RabbitMQ. gRPC para solicitudes/streams, AMQP para heartbeats y tareas.
Abrir proto: tres servicios y campos 4/5 aditivos para reemplazo/lease. SQL persiste estado;
no hay líder de ControlNode. Aclarar que promoción SQL es manual y broker no está en clúster.

## 02:20-03:40 - Namespace y transferencia (Mendel)

Mostrar cliente.log: register/login/mkdir/put/get/ls/cat. Leer tamaño 20 MiB y checksum
idéntico. Explicar 8+8+4 MiB, streams paralelos, ReportBlockStored antes del commit y
manifiesto con copias alternativas. El usuario usa rutas, no elige nodos.
No mostrar las claves del usuario de prueba ni inventar interacción en vivo al reproducir logs.

## 03:40-05:10 - RF3: abrir, editar y cerrar (Mendel y Camilo)

Mostrar rf3.log: abrir h1 en r y h2 en rw, escribir LISTO y leerlo desde h2; cat y h1 aún
ven DFSHA. Después de close h2, h1 conserva su instantánea, mientras get devuelve texto nuevo.
En código, señalar RemoteFile y commitFile: UUID nuevos y reemplazo dentro de transacción.
Explicar lease exclusivo 600 s, offsets bytes, caché completa y abort/exit que descartan.
No llamarlo POSIX ni escritura in-place. Explicar qué ocurre si vence el lease antes del close.

## 05:10-06:10 - Colocación y consistencia (Camilo)

Mostrar Placement.choosePrimary: nodos ALIVE/elegibles, rondas por archivo, puntaje 60/25/15.
Señalar CapacityReservations y único planificador de réplicas. Commit no espera factor tres:
se verifica después. PENDING es invisible; WRITTEN no significa aún todas las réplicas.

## 06:10-07:20 - Seguridad (Martín)

Mostrar Rabbit TLS config, DB_URL verify-full sin valores secretos y resultado.json.
Explicar PBKDF2, Ed25519 y AES-GCM con IV aleatorio. Mostrar rechazo-sin-tls.log y
aislamiento-usuarios.log: sus Error son el resultado esperado. Mostrar locks exclusivos
y expiración en logs. La consola RabbitMQ HTTP local no se presenta como HTTPS.

## 07:20-09:10 - Fallos controlados (equipo)

Mostrar ha-datanode.log: primera copia falla, otra sirve; recibo registra DEAD y reposición.
Mostrar ha-controlnode.log: cambio de 50071 a 50072 y descarga correcta.
Mostrar ha-broker-borrado.log y explicar outbox pendiente mientras broker estaba detenido,
luego publicación/limpieza. published_at no es acuse físico; la demo consulta los discos.
Mostrar ha-postgres.log y standby-promovido.txt: WAL esperado antes de detener/promover,
lectura posterior correcta. No afirmar failover automático ni RPO cero en un crash abrupto.
Reiterar que el primario viejo se mantiene detenido para evitar dos primarios.

## 09:10-10:20 - Pruebas y rendimiento (Camilo)

Mostrar pruebas-maven.json: 89 pruebas, 49 PostgreSQL, cero fallos/omisiones.
Distinguir pruebas AMQP simuladas de broker real y cliente gRPC acotado del clúster integrado.
Mostrar Carga en resultado.json: cuatro clientes, 160 MiB, 47,868 s, 3,343 MiB/s agregados,
cuatro checksums. Incluye login/borrado: no son percentiles ni benchmark de red aislado.

## 10:20-11:20 - Borrado, recuperación y operación (Camilo)

Mostrar borrado.log, DeleteOutbox y BlockStore.delete/save: tarea persistida + tombstone,
sin resurrección por reenvío tardío. Explicar TTL de cargas PENDING y límites de DLQ/retención.
Indicar migraciones 001/002 con respaldo y sin borrar volúmenes. Secretos generados por proyecto.

## 11:20-12:00 - Conclusiones y entrega (equipo)

Recapitular RF1/RF2/RF3 y escenarios aprobados. Reconocer un host/broker, réplica eventual,
promoción manual, ausencia de nube y no POSIX. Mostrar URL del repositorio y referencia
final verificada en GitHub; no decir main actualizado si todavía no se ha hecho el merge.
Cerrar indicando informe, código y video como entregables distintos.

## Comprobación antes de enviar el video

- Duración real entre 10:00 y 15:00; audio entendible, texto/terminal legibles.
- Todos los integrantes pueden explicar su parte; créditos correctos.
- Evidencia reproducida identificada y resultados fieles al recibo.
- Sin claves/tokens/passwords ni pantallas de secretos.
- Archivo MP4 reproducible o enlace con permisos para el profesor, según canal del curso.
- Abrir el archivo/enlace desde otra sesión y verificar acceso antes de entregar.
