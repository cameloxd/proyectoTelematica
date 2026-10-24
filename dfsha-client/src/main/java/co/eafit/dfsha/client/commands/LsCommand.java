package co.eafit.dfsha.client.commands;

import co.eafit.dfsha.client.ClientSession;

public class LsCommand implements Command {
    @Override
    public int execute(ClientSession session, String[] args) {
        System.out.println("(stub) ls en " + session.getCurrentPath());
        return 0;
    }
}