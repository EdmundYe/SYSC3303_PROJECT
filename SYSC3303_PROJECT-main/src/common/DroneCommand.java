package common;

import java.io.Serializable;
import java.util.Objects;

//Will represent a command given from the scheduler to the drone
public final class DroneCommand implements Serializable {
    private final String request_id;
    private final DroneCommandOptions command_option;
    private final int zone_id;
    private final Severity severity;

    public DroneCommand(String request_id, DroneCommandOptions command_option, int zone_id, Severity severity) {
        this.request_id = Objects.requireNonNull(request_id);
        this.command_option = Objects.requireNonNull(command_option);
        this.zone_id = zone_id;
        this.severity = Objects.requireNonNull(severity);
    }

    public String get_request_id() { return request_id; }
    public DroneCommandOptions get_command_option() { return command_option; }
    public int get_zone_id() { return zone_id; }
    public Severity getSeverity() { return severity; }

    @Override
    public String toString() {
        return "DroneCommand{" +
                "request_id='" + request_id + '\'' +
                ", command_option=" + command_option +
                ", zone_id=" + zone_id +
                ", severity=" + severity +
                '}';
    }
}