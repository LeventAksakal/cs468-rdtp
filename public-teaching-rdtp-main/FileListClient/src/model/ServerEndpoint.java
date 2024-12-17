package model;

import java.net.InetAddress;
import java.net.UnknownHostException;

public class ServerEndpoint {
    private InetAddress ipAddress;
    private int port;

    public ServerEndpoint(String ip, int port) throws UnknownHostException {
        this.ipAddress = InetAddress.getByName(ip);
        this.port = port;
    }

    public InetAddress getIpAddress() {
        return ipAddress;
    }

    public int getPort() {
        return port;
    }
}