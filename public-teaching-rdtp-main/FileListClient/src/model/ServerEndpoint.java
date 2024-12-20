package model;

import java.net.InetAddress;
import java.net.UnknownHostException;

public class ServerEndpoint {
    private InetAddress ipAddress;
    private int port;
    public NetworkMetrics metrics;

    public ServerEndpoint(String ip, int port, NetworkMetrics metrics) throws UnknownHostException {
        this.ipAddress = InetAddress.getByName(ip);
        this.port = port;
        this.metrics = metrics;
    }

    public InetAddress getIpAddress() {
        return ipAddress;
    }

    public int getPort() {
        return port;
    }

    public NetworkMetrics getMetrics() {
        return metrics;
    }
}