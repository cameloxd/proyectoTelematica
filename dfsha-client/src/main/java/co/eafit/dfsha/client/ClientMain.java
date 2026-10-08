package co.eafit.dfsha.client;

import co.eafit.dfsha.common.Env;

/** CLI del cliente DFSha. Responsable: Mendel (Fase 1: feat/client-cli-base). */
public final class ClientMain {
  public static void main(String[] args) {
    String control = Env.get("CONTROLNODE_ENDPOINT", "localhost:50051");
    System.out.println("DFSha client (esqueleto). ControlNode: " + control);
  }
}
