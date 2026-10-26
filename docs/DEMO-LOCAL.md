# Demo local reproducible en Windows

## Resultado vigente

El 2026-10-26 01:16 America/Bogota pasó la demo segura con carga y fallos del proyecto
`dfsha-demo-entrega-20261026`. [Recibo](entrega/evidencia/resultado.json) y
[estado detallado](ESTADO-DEL-PROYECTO.md). La demo anterior del 25 de octubre se conserva
como histórica: no demuestra RF3 ni TLS SQL/AMQP de esta versión.

**El standby del proyecto de entrega fue promovido. El primario antiguo queda detenido.
No ejecutar `up`, `start postgres` ni `-Resume` sobre ese proyecto.** Mantener el primario
anterior aislado hasta reconstruirlo como standby mediante un procedimiento revisado.

## Requisitos y comandos

Motor Docker Linux activo, Compose 2.24+ (overlay usa `!override`), Java 17+, Maven 3.9+,
Git Bash y los tres JAR recién construidos. Desde la raíz del repositorio:

```powershell
mvn package
.\deploy\local-demo.ps1 -ProjectName dfsha-demo-nueva -Secure -LoadClients 4
```

Sin URL de pruebas, Maven omite los casos PostgreSQL. Seguir [PRUEBAS.md](PRUEBAS.md).
La comprobación de fecha/hash de JAR del script no sustituye a las pruebas.

Para fallos controlados, usar un nombre NUEVO y asumir que el clúster queda promovido:

```powershell
.\deploy\local-demo.ps1 -ProjectName dfsha-demo-fallos-nueva -Secure -ValidateFailures -LoadClients 4
```

No ejecutar sobre datos reales. El script rechaza volúmenes existentes salvo reanudación
explícita. `-ValidateFailures` no permite `-Resume`. Para una demo segura normal NO promovida:

```powershell
.\deploy\local-demo.ps1 -ProjectName dfsha-demo-nueva -Secure -Resume
```

El modo de fallos detiene DataNode, ControlNode y RabbitMQ sucesivamente y los recupera;
finalmente detiene el primario SQL y promueve su standby después de sincronizar WAL.
Nunca usa `down -v`. Deja los volúmenes y el clúster encendido para inspección.

## Puertos y secretos

Modo `-Secure`: ControlNodes 50071/50072; DataNodes 50081-50084; PostgreSQL 55434;
AMQPS 5674; consola RabbitMQ HTTP 15673 (solo loopback). El tráfico de aplicación
gRPC/AMQP/JDBC y la replicación SQL usan TLS; la consola administrativa local no usa HTTPS.

Modo sin `-Secure`: puertos de la demo histórica 50051/50052, 50061-50064, 55433,
5673 y 15672. No representa cifrado completo de SQL/AMQP.

Una demo segura nueva usa secretos propios en `deploy/.demo/<proyecto>-secrets`.
El generador elimina CR/LF de contraseñas y añade SAN de nodos, postgres y rabbitmq.
Nunca copiar esa carpeta a la entrega ni a Git. No rotar automáticamente claves de volúmenes
existentes. No aplicar el overlay seguro a la demo antigua sin revisar certificados/migraciones.

## Qué valida y qué no

- Namespace, autenticación, put/get de 20 MiB y SHA-256 completo.
- Tres bloques y factor al menos tres WRITTEN/ALIVE, no solo tareas planificadas.
- open/read/write/close, visibilidad al commit y snapshot de lector abierto.
- Borrado lógico, outbox y ausencia física de UUID en los cuatro nodos.
- Con Secure: rechazo de plaintext SQL/gRPC, listener AMQP solo TLS, aislamiento y locks/TTL.
- Con LoadClients: procesos concurrentes con usuarios diferentes, checksums y métrica agregada.
- Con ValidateFailures: descarga desde réplica, DEAD/reposición, cambio de ControlNode,
  outbox durante caída del broker y lectura tras promoción SQL manual.

Los `Error: UNAVAILABLE`, `NOT_FOUND` y `FAILED_PRECONDITION` de los logs de pruebas negativas
son rechazos esperados, comprobados individualmente. Otros errores no se aceptan. Los avisos
de Unsafe/native-access provienen de dependencias en JDK 24, no son fallos de integridad.
No se afirma HA del host, pérdida cero de WAL no replicado ni rendimiento de producción.

## Conservar evidencia y detener

`deploy/.demo/<proyecto>/resultado.json`, logs y archivos de prueba son locales/ignorados.
La carpeta `docs/entrega/evidencia` incluye solo recibo/logs sanitizados de la pasada aprobada;
no incluye contraseñas, claves ni archivos binarios aleatorios. Un error de consulta a un
DataNode no se interpreta como bloque ausente.

Para detener SOLO el proyecto de entrega ya promovido, sin borrar datos ni arrancar servicios:

```powershell
docker compose -p dfsha-demo-entrega-20261026 -f deploy/docker-compose.yml -f deploy/docker-compose.local-demo.yml stop
```

No ejecutar luego `up` sobre él. Para repetir la validación, detener el proyecto anterior
para liberar sus puertos y crear uno NUEVO con nombre diferente. Detenerlo solo cuando no
se necesite para la demostración; no parar la demo histórica por accidente.

Si falla: conservar etapa/logs y no presentar un resultado parcial como aprobado. No ejecutar
`escenarios.sh` sobre el clúster promovido; el runner vigente ya cubre los escenarios descritos.
