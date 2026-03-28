package common;

import java.io.Serializable;
import java.util.Objects;

/**
 * Represents a command given from the scheduler to the drone
 * Extended in Iteration 4 to include fault information for fault injection
 */
public final class DroneCommand implements Serializable {
    private final String request_id;
    private final DroneCommandOptions command_option;
    private final int zone_id;
    private final Severity severity;

    // Fault information fields for Iteration 4
    private final FaultType faultType;
    private final int faultDelaySeconds;

    /**
     * Constructor for DroneCommand with fault information
     * Extended in Iteration 4 to support fault injection
     *
     * @param request_id Unique request identifier
     * @param command_option Type of command (DISPATCH, etc.)
     * @param zone_id Target zone ID
     * @param severity Fire severity level
     * @param faultType Type of fault to inject (NONE, DRONE_STUCK, NOZZLE_JAM, PACKET_LOSS)
     * @param faultDelaySeconds Delay in seconds before fault is triggered
     */
    public DroneCommand(String request_id, DroneCommandOptions command_option, int zone_id,
                        Severity severity, FaultType faultType, int faultDelaySeconds) {
        this.request_id = Objects.requireNonNull(request_id);
        this.command_option = Objects.requireNonNull(command_option);
        this.zone_id = zone_id;
        this.severity = Objects.requireNonNull(severity);
        this.faultType = faultType != null ? faultType : FaultType.NONE;
        this.faultDelaySeconds = faultDelaySeconds;
    }

    /**
     * Constructor for DroneCommand without fault information
     * Used for backward compatibility
     */
    public DroneCommand(String request_id, DroneCommandOptions command_option, int zone_id, Severity severity) {
        this(request_id, command_option, zone_id, severity, FaultType.NONE, 0);
    }

    public String get_request_id() { return request_id; }
    public DroneCommandOptions get_command_option() { return command_option; }
    public int get_zone_id() { return zone_id; }
    public Severity getSeverity() { return severity; }
    public FaultType getFaultType() { return faultType; }
    public int getFaultDelaySeconds() { return faultDelaySeconds; }

    @Override
    public String toString() {
        String faultInfo = (faultType != FaultType.NONE) ?
                ", fault=" + faultType + ", faultDelay=" + faultDelaySeconds : "";

        return "DroneCommand{" +
                "request_id='" + request_id + '\'' +
                ", command_option=" + command_option +
                ", zone_id=" + zone_id +
                ", severity=" + severity +
                faultInfo +
                '}';
    }
}