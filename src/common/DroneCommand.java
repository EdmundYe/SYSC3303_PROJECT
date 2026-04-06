package common;

import java.io.Serializable;
import java.util.Objects;

// Will represent a command given from the scheduler to the drone
public final class DroneCommand implements Serializable {
    private final String request_id;
    private final DroneCommandOptions command_option;
    private final int zone_id;
    private final Severity severity;
    private final FaultType faultType;
    private final int faultDelaySeconds;
    private final int agentAmount;

    // Old constructor kept for backward compatibility with deprecated tests
    public DroneCommand(String request_id,
                        DroneCommandOptions command_option,
                        int zone_id,
                        Severity severity,
                        int agentAmount) {
        this(request_id, command_option, zone_id, severity, agentAmount, FaultType.NONE, 0);
    }

    // New Iteration 4 constructor
    public DroneCommand(String request_id,
                        DroneCommandOptions command_option,
                        int zone_id,
                        Severity severity,
                        int agentAmount,
                        FaultType faultType,
                        int faultDelaySeconds) {
        this.request_id = Objects.requireNonNull(request_id);
        this.command_option = Objects.requireNonNull(command_option);
        this.zone_id = zone_id;
        this.severity = Objects.requireNonNull(severity);
        this.agentAmount = agentAmount;
        this.faultType = faultType != null ? faultType : FaultType.NONE;
        this.faultDelaySeconds = Math.max(0, faultDelaySeconds);
    }

    public String get_request_id() { return request_id; }
    public DroneCommandOptions get_command_option() { return command_option; }
    public int get_zone_id() { return zone_id; }
    public Severity getSeverity() { return severity; }
    public int getAgentAmount() {return agentAmount;}
    public FaultType getFaultType() { return faultType; }
    public int getFaultDelaySeconds() { return faultDelaySeconds; }

    @Override
    public String toString() {
        return "DroneCommand{" +
                "request_id='" + request_id + '\'' +
                ", command_option=" + command_option +
                ", zone_id=" + zone_id +
                ", severity=" + severity +
                ", faultType=" + faultType +
                ", faultDelaySeconds=" + faultDelaySeconds +
                '}';
    }
}