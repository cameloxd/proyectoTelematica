package co.eafit.dfsha.client.commands;

import co.eafit.dfsha.client.ClientSession;

public class CdCommand implements Command {
    @Override
    public int execute(ClientSession session, String[] args) {
        if (args.length != 1) {
            System.out.println("Uso: cd <ruta>");
            return 1;
        }
        String target = args[0];
        if (target.equals("..")) {
            String p = session.getCurrentPath();
            if (!p.equals("/")) {
                int idx = p.lastIndexOf('/');
                session.setCurrentPath(idx <= 0 ? "/" : p.substring(0, idx));
            }
        } else if (target.startsWith("/")) {
            session.setCurrentPath(target);
        } else {
            String p = session.getCurrentPath();
            session.setCurrentPath(p.equals("/") ? "/" + target : p + "/" + target);
        }
        return 0;
    }
}