package common;

public class DroneInfo {
    public int droneId;
    public boolean available;
    public boolean busy;
    public FireEvent assignedEvent;

    public DroneInfo(int DroneId){
        this.droneId = DroneId;
        this.busy = false;
        this.available = true;
        this.assignedEvent = null;
    }
}
