package common;

import java.net.InetAddress;

public class DroneInfo {
    public final int droneId;

    public boolean available;
    public boolean busy;
    public FireEvent assignedEvent;

    public DroneState lastKnownState;
    public Integer currentZoneId;
    public Integer remainingAgent;
    public int missionsCompleted;
    public long lastStatusTimeMs;

    private InetAddress listenAddress;
    private int listenPort;

    public DroneInfo(int droneId) {
        this.droneId = droneId;
        this.busy = false;
        this.available = true;
        this.assignedEvent = null;

        this.lastKnownState = DroneState.IDLE;
        this.currentZoneId = null;
        this.remainingAgent = 100;
        this.missionsCompleted = 0;
        this.lastStatusTimeMs = System.currentTimeMillis();

        this.listenPort = 6100 + droneId;
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

    public boolean isDispatchable() {
        return available && !busy && listenAddress != null;
    }

    public void markBusy(FireEvent event) {
        this.available = false;
        this.busy = true;
        this.assignedEvent = event;
        this.currentZoneId = event.getZoneId();
        this.lastKnownState = DroneState.EN_ROUTE;
        this.lastStatusTimeMs = System.currentTimeMillis();
    }

    public void markIdle() {
        this.available = true;
        this.busy = false;
        this.assignedEvent = null;
        this.currentZoneId = null;
        this.lastKnownState = DroneState.IDLE;
        this.lastStatusTimeMs = System.currentTimeMillis();
    }

    public void applyStatus(DroneStatus status) {
        this.lastKnownState = status.getState();
        this.currentZoneId = status.get_zone_id();
        this.remainingAgent = status.get_remaining_agent();
        this.lastStatusTimeMs = status.get_status_time_ms();

        if (status.getState() == DroneState.IDLE || status.getState() == DroneState.DONE) {
            this.available = true;
            this.busy = false;
        } else {
            this.available = false;
            this.busy = true;
        }
    }
}