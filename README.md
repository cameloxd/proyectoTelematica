# DFSha

Sistema de archivos distribuido por bloques, en Java, para ST0263/SI3007, EAFIT 2026-2.
Equipo: Camilo, Mendel y Martín. Separa metadatos (ControlNodes/PostgreSQL) de datos
(DataNodes), con replicación asíncrona y cliente CLI. No es un montaje POSIX/FUSE.

## Empezar aquí

- [Estado y evidencia](docs/ESTADO-DEL-PROYECTO.md).
- [Guía del código](docs/GUIA-DEL-CODIGO.md) y [pruebas](docs/PRUEBAS.md).
- [Demo reproducible y advertencia de promoción](docs/DEMO-LOCAL.md).
- [Informe y lista de entrega](docs/entrega/INFORME.md).
- [Guion de video de 12 minutos](docs/entrega/GUION-VIDEO.md).
- [Contribución y revisiones](CONTRIBUTING.md).

## Capacidades

- RF1: usuarios, login y namespace por propietario: ls/cd/pwd/mkdir/rmdir/rm.
- RF2: put/get paralelos, SHA-256 y lectura desde réplicas con failover.
- RF3: open/read/write/close con instantánea local; un escritor con lease, publicación
  atómica de una nueva versión al cerrar y lectores abiertos que conservan su instantánea.
  `abort` o salir sin cerrar descarta los cambios. `cat`, `lock` y `unlock` también disponibles.
- Bloques de 8 MiB, colocación por rondas/puntaje y factor de réplica configurable (3 en la demo).
- Outbox transaccional para borrados; reintentos, expiración de cargas abandonadas y
  tombstones persistentes que impiden recrear un UUID borrado mediante escrituras tardías.
- AES-256-GCM en disco, tokens Ed25519, PBKDF2 y clave de registro de nodos.
- gRPC con TLS. El overlay seguro añade TLS verificado a AMQP, JDBC y replicación PostgreSQL.
- Dos ControlNodes activos, cuatro DataNodes y PostgreSQL primario/standby. La promoción
  de PostgreSQL es manual y requiere aislar el primario anterior; no es HA automática.

## Compilar y probar

Java 17+, Maven 3.9+ y, para la demo, Docker Desktop Linux, Git Bash y Compose 2.24+.

```bash
mvn clean verify
```

Sin `DFSHA_TEST_DB_URL`, se omiten las pruebas PostgreSQL: un build verde no basta.
[PRUEBAS.md](docs/PRUEBAS.md) explica cómo incluirlas. La CI configura PostgreSQL de pruebas;
su resultado debe consultarse en GitHub, no inferirse de la existencia del workflow.

## Demo local segura (Windows)

Desde la raíz del repositorio, después de generar los JAR con `mvn package`:

```powershell
.\deploy\local-demo.ps1 -ProjectName dfsha-demo-nueva -Secure -LoadClients 4
```

Para probar además fallos controlados, usar OTRO nombre nuevo y añadir `-ValidateFailures`.
Ese modo promueve el standby y deja el primario antiguo detenido: **no volver a ejecutar
`up` ni reanudar ese proyecto después de la promoción**. No usar la demo con datos reales.
No se eliminan volúmenes ni se toca un proyecto anterior. Puertos y secretos: [DEMO-LOCAL.md](docs/DEMO-LOCAL.md).

Resultado local del 2026-10-26 01:16 America/Bogota: RF1/RF2/RF3, TLS, cuatro clientes,
integridad, replicación, borrado y fallos de DataNode/ControlNode/broker/PostgreSQL aprobados.
La batería Maven aprobó 89 pruebas sin omisiones. Esto no certifica nube ni producción.

## Estructura

```text
dfsha-proto/        contrato gRPC v1.2, aditivo
dfsha-common/       entorno, TLS, tokens, rutas, hashes y AMQP
dfsha-controlnode/  metadatos, versiones, reservas, outbox y planificación
dfsha-datanode/     bloques inmutables cifrados y réplica física
dfsha-client/       CLI y API de archivos abiertos
deploy/            Compose, secretos, esquema, migraciones y scripts
docs/              explicación, evidencia y material de entrega
```

Para bases existentes aplicar primero [las migraciones 001 y 002](deploy/migrations/README.md)
con respaldo y ventana coordinada. Nunca actualizar borrando volúmenes. Los diseños antiguos
se conservan como históricos en [el índice](docs/README.md).
