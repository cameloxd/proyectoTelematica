package co.eafit.dfsha.client;

import static java.nio.charset.StandardCharsets.UTF_8;

import co.eafit.dfsha.common.DfsPaths;
import co.eafit.dfsha.common.Env;
import co.eafit.dfsha.common.Hash;
import co.eafit.dfsha.grpc.v1.DirectoryEntry;
import co.eafit.dfsha.grpc.v1.LockResponse;
import io.grpc.StatusRuntimeException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Consola del cliente DFSha: lee un comando por línea y llama a {@link DfsClient}.
 *
 * <pre>
 *   java -jar dfsha-client/target/dfsha-client.jar
 *   printf 'login ana 1234\nls\n' | java -jar dfsha-client.jar      (también sirve para scripts)
 * </pre>
 * Variables: CONTROLNODE_ENDPOINTS (por defecto localhost:50051,localhost:50052) y TLS_CA_FILE.
 */
public final class ClientMain {
  private static final String HELP = """
      Comandos:
        register <usuario> <clave>        crear un usuario
        login <usuario> <clave>           iniciar sesión
        ls [ruta]  cd <ruta>  pwd  mkdir <ruta>  rmdir <ruta>  rm <ruta>
        put <archivo-local> [ruta]        subir un archivo (send)
        get <ruta> [archivo-local]        bajar un archivo (receive)
        cat <ruta> [offset] [bytes]       leer un pedazo sin bajar todo (read)
        lock <ruta> [segundos]            bloquear un archivo (60 s por defecto)
        unlock <ruta>                     liberar el lock
        exit
      """;

  public static void main(String[] args) throws Exception {
    List<String> endpoints = Env.getList("CONTROLNODE_ENDPOINTS", "localhost:50051,localhost:50052");
    Map<String, String> myLocks = new HashMap<>();             // ruta -> lock_id de esta sesión
    String cwd = "/";

    try (DfsClient dfs = new DfsClient(endpoints);
         BufferedReader in = new BufferedReader(new InputStreamReader(System.in, UTF_8))) {
      System.out.println("DFSha. ControlNodes: " + endpoints + ". Escribe help.");
      while (true) {
        System.out.print("dfsha:" + cwd + "> ");
        String line = in.readLine();
        if (line == null) return;                              // fin de la entrada (Ctrl+D o fin del script)
        String[] a = line.trim().split("\\s+");
        if (a[0].isEmpty()) continue;
        try {
          switch (a[0]) {
            case "help" -> System.out.print(HELP);
            case "exit", "quit" -> {
              return;
            }
            case "register" -> {
              need(a, 3);
              dfs.register(a[1], a[2]);
              System.out.println("Usuario " + a[1] + " creado");
            }
            case "login" -> {
              need(a, 3);
              dfs.login(a[1], a[2]);
              cwd = "/";
              System.out.println("Sesión iniciada como " + a[1]);
            }
            case "pwd" -> System.out.println(cwd);
            case "cd" -> {
              need(a, 2);
              String target = DfsPaths.resolve(cwd, a[1]);
              dfs.list(target);                                // si no existe, lanza NOT_FOUND
              cwd = target;
            }
            case "ls" -> {
              for (DirectoryEntry e : dfs.list(DfsPaths.resolve(cwd, a.length > 1 ? a[1] : "."))) {
                System.out.printf("%s %12d  %s%n", e.getDirectory() ? "d" : "-", e.getSizeBytes(),
                    DfsPaths.name(e.getPath()) + (e.getDirectory() ? "/" : ""));
              }
            }
            case "mkdir" -> {
              need(a, 2);
              System.out.println(dfs.mkdir(DfsPaths.resolve(cwd, a[1])));
            }
            case "rm", "rmdir" -> {
              need(a, 2);
              System.out.println(dfs.delete(DfsPaths.resolve(cwd, a[1])));
            }
            case "put" -> {
              need(a, 2);
              Path local = Path.of(a[1]);
              String remote = DfsPaths.resolve(cwd, a.length > 2 ? a[2] : local.getFileName().toString());
              long start = System.nanoTime();
              dfs.put(local, remote);
              System.out.printf("Subido %s (%d bytes) en %.2f s. SHA-256 %s%n", remote, Files.size(local),
                  seconds(start), Hash.sha256(local));
            }
            case "get" -> {
              need(a, 2);
              String remote = DfsPaths.resolve(cwd, a[1]);
              Path local = Path.of(a.length > 2 ? a[2] : DfsPaths.name(remote));
              long start = System.nanoTime();
              dfs.get(remote, local);
              System.out.printf("Descargado en %s (%d bytes) en %.2f s. SHA-256 %s%n", local, Files.size(local),
                  seconds(start), Hash.sha256(local));
            }
            case "cat" -> {
              need(a, 2);
              long offset = a.length > 2 ? Long.parseLong(a[2]) : 0;
              int bytes = a.length > 3 ? Integer.parseInt(a[3]) : 1024;
              System.out.println(new String(dfs.read(DfsPaths.resolve(cwd, a[1]), offset, bytes), UTF_8));
            }
            case "lock" -> {
              need(a, 2);
              String path = DfsPaths.resolve(cwd, a[1]);
              LockResponse lock = dfs.lock(path, a.length > 2 ? Integer.parseInt(a[2]) : 60);
              myLocks.put(path, lock.getLockId());
              System.out.println("Lock de " + path + " hasta las " + LocalTime.ofInstant(
                  Instant.ofEpochSecond(lock.getExpiresAtEpochSeconds()), ZoneId.systemDefault()));
            }
            case "unlock" -> {
              need(a, 2);
              String path = DfsPaths.resolve(cwd, a[1]);
              if (!myLocks.containsKey(path)) throw new IllegalArgumentException("esta sesión no tiene lock sobre " + path);
              dfs.unlock(myLocks.remove(path));
              System.out.println("Lock liberado");
            }
            default -> System.out.println("Comando desconocido: " + a[0] + " (escribe help)");
          }
        } catch (StatusRuntimeException e) {
          System.out.println("Error: " + e.getStatus().getCode() + " - " + e.getStatus().getDescription());
        } catch (Exception e) {
          System.out.println("Error: " + e.getMessage());
        }
      }
    }
  }

  private static void need(String[] args, int count) {
    if (args.length < count) throw new IllegalArgumentException("faltan argumentos (escribe help)");
  }

  private static double seconds(long startNanos) {
    return (System.nanoTime() - startNanos) / 1e9;
  }
}
