package co.eafit.dfsha.client;

import co.eafit.dfsha.client.commands.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

public class ClientMain {

    public static void main(String[] args) throws Exception {
        String endpoint = System.getenv().getOrDefault(
                "CONTROLNODE_ENDPOINT", "localhost:50051");

        ClientSession session = new ClientSession(endpoint);
        System.out.println("DFSha Client");
        System.out.println("ControlNode: " + endpoint);

        Map<String, Command> commands = new HashMap<>();
        commands.put("ls",    new LsCommand());
        commands.put("cd",    new CdCommand());
        commands.put("mkdir", new MkdirCommand());
        commands.put("rmdir", new RmdirCommand());
        commands.put("rm",    new RmCommand());
        commands.put("pwd",   (s, a) -> { System.out.println(s.getCurrentPath()); return 0; });
        commands.put("help",  (s, a) -> {
            System.out.println("Comandos: ls, cd, pwd, mkdir, rmdir, rm, exit");
            return 0;
        });

        try (BufferedReader br = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while (true) {
                System.out.print(session.getPrompt() + "> ");
                line = br.readLine();
                if (line == null) break;
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.equals("exit") || line.equals("quit")) break;

                String[] parts = line.split("\\s+");
                String cmd = parts[0];
                String[] cmdArgs = new String[parts.length - 1];
                System.arraycopy(parts, 1, cmdArgs, 0, cmdArgs.length);

                Command handler = commands.get(cmd);
                if (handler == null) {
                    System.out.println("Error: comando desconocido: " + cmd);
                    continue;
                }
                try {
                    handler.execute(session, cmdArgs);
                } catch (Exception e) {
                    System.out.println("Error: " + e.getMessage());
                }
            }
        }
    }
}