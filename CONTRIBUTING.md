# Guia de contribucion

## 1. Ramas

| Rama | Para que sirve | Quien escribe en ella |
|---|---|---|
| `main` | Entregas del curso. Siempre compila. | Solo merges desde `develop` al cerrar cada fase (con tag). |
| `develop` | Integracion del equipo. | Nadie hace push directo: solo Pull Requests. |
| `<tipo>/<ambito>-<tarea>` | Una tarea. | Su autor. Vive pocos dias. |

Nombres de rama: `feat/client-cli-base`, `fix/control-reserva-bytes`, `docs/hito3-replicacion`,
`chore/deploy-cluster-local`, `test/e2e-upload-download`.

## 2. Flujo de trabajo (una tarea = una rama = un PR)

```bash
git checkout develop && git pull                 # 1. parte siempre de develop actualizado
git checkout -b feat/client-cli-base             # 2. rama de la tarea
# ... trabajar, commits pequenos ...
git fetch origin && git rebase origin/develop    # 3. ponerse al dia ANTES de abrir el PR
mvn -q -DskipTests package                       # 4. verificar que compila (y mvn test)
git push -u origin feat/client-cli-base          # 5. subir la rama
# 6. abrir Pull Request hacia develop; otra persona lo revisa; "Squash and merge"
git checkout develop && git pull && git branch -d feat/client-cli-base
```

## 3. Commits - Conventional Commits

Formato:

```text
<tipo>(<ambito>): <descripcion en infinitivo, minuscula, sin punto final>

<cuerpo opcional: POR QUE se hizo, no que lineas cambiaron>

<pie opcional: Refs #12 | BREAKING CHANGE: ...>
```

**Tipos:** `feat` (funcionalidad nueva) - `fix` (correccion de bug) - `refactor` (reorganizar sin cambiar
comportamiento) - `test` - `docs` - `build` (Maven, dependencias) - `chore` (mantenimiento, infra, scripts) -
`perf` - `ci`.

**Ambitos:** `client`, `control`, `data`, `proto`, `common`, `deploy`, `db`, `docs`, `repo`.

**Reglas:**
1. Primera linea de maximo 72 caracteres.
2. Un commit = un cambio logico. Si necesitas escribir "y" en el mensaje, son dos commits.
3. Un cambio que rompe el contrato se marca con `!`: `feat(proto)!: eliminar PublishHeartbeat`.
4. Nunca commitear `.env`, `secrets/`, claves ni `target/`.
5. El titulo del PR sigue el mismo formato (al hacer squash, ese titulo queda como el commit en `develop`).

**Bien / mal:**

| Mal | Bien |
|---|---|
| `cambios` | `feat(client): agregar comando ls` |
| `arreglando cosas del controlnode` | `fix(control): evitar sobreasignacion de espacio en AllocateFile` |
| `Update ControlNodeMain.java` | `refactor(control): extraer repositorio de DataNodes` |
| `feat: todo el cliente` | varios commits: `feat(client): ...`, `test(client): ...` |

## 4. Pull Requests
- Hacia `develop`, con la plantilla (`.github/pull_request_template.md`).
- Minimo **1 aprobacion** de otra persona del equipo.
- El autor debe poder explicar cada parte del codigo, incluido el generado con IA.
- Cambios a `dfsha.proto`: avisar al grupo antes de abrir el PR; Martin los aprueba.

## 5. Definicion de "terminado"
Compila, tiene al menos una prueba o una forma documentada de probarlo, esta revisado y mergeado a `develop`.
