package co.eafit.dfsha.client;

public class ClientSession {

    private final String controlNodeEndpoint;
    private String currentPath = "/";

    public ClientSession(String controlNodeEndpoint) {
        this.controlNodeEndpoint = controlNodeEndpoint;
    }

    public String getControlNodeEndpoint() { return controlNodeEndpoint; }
    public String getCurrentPath()         { return currentPath; }
    public void setCurrentPath(String p)   { this.currentPath = p; }

    public String getPrompt() {
        return "dfsha:" + currentPath;
    }
}