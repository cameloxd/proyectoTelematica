# Demostración local en Windows

## Estado

El script está preparado; su existencia **no demuestra una ejecución satisfactoria**. Registrar el resultado real de la terminal y conservar `resultado.json` solamente cuando termine con `DEMO BASICA COMPLETADA`.

Esta demo usa la rama `chore/local-demo`, basada en la outbox publicada `ccd6966`; esa corrección aún requiere PR/revisión/integración hacia develop. No se desplegó en nube ni se certificó el proyecto completo.

## Ejecutar

Requisitos: Docker Desktop con motor Linux activo, Java 17+, Git Bash y los tres JAR recientes obtenidos con `mvn package`. Usar la PowerShell del usuario con acceso a Docker; el entorno restringido de Codex no puede abrir el canal del motor Docker de este equipo.

Desde la raíz del repositorio:

```powershell
.\deploy\local-demo.ps1
```

El script utiliza el Compose principal con un overlay de imagen local. Copia solo los tres JAR y un Dockerfile a un contexto ignorado por Git, sin volver a descargar Maven/dependencias ni incluir secretos en la imagen. Comprueba que los JAR no sean anteriores a las fuentes/poms; esto es una comprobación de vigencia, no sustituye a las pruebas Maven ni a la trazabilidad por hash que registra el resultado.

El script original `gen-secrets.sh` genera los secretos faltantes; los existentes se conservan y nunca se muestran sus valores. Los certificados cubren localhost y los nombres internos de los nodos. No usar este bootstrap como configuración de seguridad de producción.

## Qué comprueba

- Arranque de la configuración: dos ControlNodes, cuatro DataNodes, RabbitMQ y PostgreSQL primario/standby.
- Registro de cuatro DataNodes ALIVE.
- Usuario desechable, login, directorio, subida de 20 MiB y un texto pequeño.
- Descarga del archivo y comparación de SHA-256 completo.
- Lectura parcial, lock y unlock mediante la CLI.
- Tres bloques para los 20 MiB y al menos tres copias WRITTEN/ALIVE por bloque.
- Borrado del archivo de prueba, tareas de outbox publicadas y ausencia de sus UUID en los discos de los cuatro DataNodes.

No basta con el código de salida de la CLI: también se rechaza cualquier `Error:` y se comprueban checksum, SQL y archivos físicos. Un problema al consultar un DataNode cuenta como bloque todavía presente, no como eliminación exitosa.

No demuestra fallos bajo carga, exclusión de locks entre clientes, expiración, caída/recuperación de RabbitMQ, failover de ControlNode/PostgreSQL, TLS negativo, DLQ, escrituras tardías ni inmutabilidad ante reenvíos distintos. No se ejecuta `escenarios.sh` porque incluye una promoción del standby.

## Recursos y evidencia

- Proyecto Compose único: `dfsha-demo-<fecha-hora>`; rechaza nombres con volúmenes existentes.
- Comprueba puertos antes de iniciar y no detiene servicios ajenos. Publica gRPC en 50051/50052 y 50061–50064, PostgreSQL en 55433 y AMQP en 5673. La consola RabbitMQ usa 15672.
- Artefactos ignorados por Git: `deploy/.demo/<proyecto>/cliente.log`, `borrado.log`, `resultado.json` y archivos de prueba.
- `resultado.json` registra commit, hashes de los JAR, SHA-256 original/descarga, bloques, factor observado y resultado del borrado físico. No contiene la clave del usuario desechable.
- El clúster queda encendido y conserva sus volúmenes. El script imprime el comando `stop` limitado a su proyecto. No usa `down -v`, no promueve bases ni borra archivos de otros usuarios.

Si falla, conservar la salida con `DEMO NO COMPLETADA`, la etapa y los logs. No presentar esa ejecución como aprobada ni repetir con datos de usuarios reales.
