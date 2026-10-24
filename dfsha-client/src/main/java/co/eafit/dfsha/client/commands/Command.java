package co.eafit.dfsha.client.commands;

import co.eafit.dfsha.client.ClientSession;

@FunctionalInterface
public interface Command {
    int execute(ClientSession session, String[] args) throws Exception;
}