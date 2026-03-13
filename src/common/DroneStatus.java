package common;

import java.io.Serializable;

// Status update to be sent from drone to scheduler or scheduler to fireIncidentSubsystem
public final class DroneStatus implements Serializable {
    private final int drone_id;
    private final DroneState state;
    private final Integer zone_id;           // nullable if not assigned
    private final Integer remaining_agent;   // nullable if unknown
    private final long status_time_ms;

    public DroneStatus(int drone_id, DroneState state, Integer zone_id, Integer remaining_agent) {
        this(drone_id, state, zone_id, remaining_agent, System.currentTimeMillis());
    }

    public DroneStatus(int drone_id, DroneState state, Integer zone_id,
                       Integer remaining_agent, long status_time_ms) {
        this.drone_id = drone_id;
        this.state = state;
        this.zone_id = zone_id;
        this.remaining_agent = remaining_agent;
        this.status_time_ms = status_time_ms;
    }

    public int get_drone_id() {
        return drone_id;
    }

    public DroneState getState() {
        return state;
    }

    public Integer get_zone_id() {
        return zone_id;
    }

    public Integer get_remaining_agent() {
        return remaining_agent;
    }

    public long get_status_time_ms() {
        return status_time_ms;
    }

    @Override
    public String toString() {
        return "DroneStatus{" +
                "drone_id=" + drone_id +
                ", state=" + state +
                ", zone_id=" + zone_id +
                ", remaining_agent=" + remaining_agent +
                ", status_time_ms=" + status_time_ms +
                '}';
    }
}
