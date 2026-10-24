package co.eafit.dfsha.client.commands;

import co.eafit.dfsha.client.ClientSession;

public class RmdirCommand implements Command {
    @Override
    public int execute(ClientSession session, String[] args) {
        if (args.length != 1) {
            System.out.println("Uso: rmdir <ruta>");
            return 1;
        }
        System.out.println("(stub) rmdir " + args[0]);
        return 0;
    }
}