package common;

import java.net.InetAddress;

public class DroneInfo {
    public int droneId;
    public boolean available;
    public boolean busy;
    public FireEvent assignedEvent;
    private InetAddress listenAddress;
    private int listenPort;

    public DroneInfo(int DroneId){
        this.droneId = DroneId;
        this.busy = false;
        this.available = true;
        this.assignedEvent = null;
        this.listenPort = 6100 + DroneId;
    }

    public void setListenAddress(InetAddress listenAddress) {
        this.listenAddress = listenAddress;
    }

    public void setListenPort(int port) {
        this.listenPort = port;
    }

    public InetAddress getListenAddress() {
        return listenAddress;
    }

    public int getListenPort() {
        return listenPort;
    }
}
