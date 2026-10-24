# Metadatos del ControlNode

El ControlNode es la fuente de verdad de metadatos; los DataNodes solo guardan bloques opacos. PostgreSQL conserva usuarios, namespace, archivos, bloques, ubicaciones y el catálogo de DataNodes.

## Catálogo de DataNodes

`node_name` es la identidad estable del proceso. `RegisterDataNode` hace *upsert* por ese campo: al reiniciar, el mismo nodo conserva su `datanode_id`, sus reservas y sus ubicaciones. `endpoint` es la dirección anunciada a los clientes (debe ser alcanzable desde ellos); `internal_endpoint` se reserva para tráfico dentro del clúster.

Un nodo entra como `ALIVE`. Fase 2 actualizará `last_heartbeat_at` desde RabbitMQ y cambiará a `SUSPECT` tras 15 segundos sin latido y a `DEAD` tras 30. Un nodo `DECOMMISSIONING` no recibe asignaciones nuevas.

## Capacidad y propietarios de los campos

| Campo | Quién lo actualiza | Significado |
| --- | --- | --- |
| `capacity_bytes`, `free_bytes`, `active_transfers`, `stored_blocks` | DataNode al registrarse y luego heartbeat | Estado físico observado |
| `reserved_bytes` | Solo ControlNode | Espacio prometido a ubicaciones `PLANNED` |
| `available_bytes` | PostgreSQL (columna generada) | `free_bytes - reserved_bytes` |
| `status` | ControlNode | Estado de disponibilidad calculado |

La Fase 2 reserva espacio mediante un `UPDATE` condicional dentro de la transacción de `AllocateFile`; así nunca se sobreasigna una capacidad que dos clientes observaron simultáneamente. Los bloques y sus ubicaciones se vinculan por `dfs_block` y `block_location`, permitiendo varias réplicas en el futuro.

## Adaptadores de persistencia de Fase 1

`JdbcUserRepository`, `JdbcDirectoryRepository`, `JdbcFileRepository`, `JdbcBlockRepository`, `JdbcBlockLocationRepository` y `JdbcDataNodeRepository` contienen las consultas del esquema. `JdbcFileFlowRepository` agrega las transacciones completas de asignación, reporte, commit y manifiesto de la Fase 2.

### Prueba manual contra PostgreSQL

Con la infraestructura levantada (`docker compose up postgres` desde `deploy/`), iniciar el ControlNode con `DB_URL=jdbc:postgresql://localhost:5432/dfsha`, `DB_USER=dfsha` y la contraseña del secreto. Registrar dos veces el mismo `node_name` con endpoints actualizados: debe mantenerse el mismo `datanode_id`, quedar `ALIVE` y conservar `reserved_bytes`. La prueba unitaria de `NodeRegistryGrpcService` cubre además solicitudes inválidas sin requerir Docker.
