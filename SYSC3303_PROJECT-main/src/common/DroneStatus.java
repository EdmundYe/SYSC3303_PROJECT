package common;

import java.io.Serializable;

//Status update to be sent from drone to scheduler or scheduler to fireIncidentSubsystem
public final class DroneStatus implements Serializable {
    private final int drone_id;
    private final DroneState state;
    private final Integer zone_id;           // nullable if not assigned
    private final Integer remaining_agent;   // nullable for Iteration 1

    public DroneStatus(int drone_id, DroneState state, Integer zone_id, Integer remaining_agent) {
        this.drone_id = drone_id;
        this.state = state;
        this.zone_id = zone_id;
        this.remaining_agent = remaining_agent;
    }

    public int get_drone_id() { return drone_id; }
    public DroneState getState() { return state; }
    public Integer get_zone_id() { return zone_id; }
    public Integer get_remaining_agent() { return remaining_agent; }

    @Override
    public String toString() {
        return "DroneStatus{" +
                "drone_id=" + drone_id +
                ", state=" + state +
                ", zone_id=" + zone_id +
                ", remaining_agent=" + remaining_agent +
                '}';
    }
}
