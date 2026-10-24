package co.eafit.dfsha.client.commands;

import co.eafit.dfsha.client.ClientSession;

public class MkdirCommand implements Command {
    @Override
    public int execute(ClientSession session, String[] args) {
        if (args.length != 1) {
            System.out.println("Uso: mkdir <ruta>");
            return 1;
        }
        System.out.println("(stub) mkdir " + args[0]);
        return 0;
    }
}