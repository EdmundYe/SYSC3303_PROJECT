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

    public double posX = 0;
    public double posY = 0;
    public int destinationZone = -1;

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
        // Cannot dispatch if already busy
        if (busy) {
            return false;
        }

        // Cannot dispatch if FAULTED or OFFLINE (Iteration 4)
        if (lastKnownState == DroneState.FAULTED || lastKnownState == DroneState.OFFLINE) {
            return false;
        }

        // Can only dispatch if available and reachable
        return available;
    }

    public void markBusy(FireEvent event) {
        this.available = false;
        this.busy = true;
        this.assignedEvent = event;
        this.currentZoneId = event.getZoneId();
        this.destinationZone = event.getZoneId();
        this.lastKnownState = DroneState.EN_ROUTE;
        this.lastStatusTimeMs = System.currentTimeMillis();
    }

    public void markIdle() {
        this.available = true;
        this.busy = false;
        this.assignedEvent = null;
        this.currentZoneId = null;
        this.currentZoneId = -1;
        this.lastKnownState = DroneState.IDLE;
        this.lastStatusTimeMs = System.currentTimeMillis();
    }

    public void applyStatus(DroneStatus status) {
        this.lastKnownState = status.getState();
        this.currentZoneId = status.get_zone_id();
        this.remainingAgent = status.get_remaining_agent();
        this.lastStatusTimeMs = status.get_status_time_ms();
        this.posX = status.getPosX();
        this.posY = status.getPosY();

        if (status.getState() == DroneState.IDLE || status.getState() == DroneState.DONE) {
            this.available = true;
            this.busy = false;
            this.destinationZone = -1;
        } else {
            this.available = false;
            this.busy = true;
        }
    }

    public double estimateSecondsToZone(int targetZone){
        int[] dest = ZoneMap.get(targetZone);
        double dist = Math.hypot(dest[0] - posX, dest[1] - posY);
        return dist / 15.0;
    }
}