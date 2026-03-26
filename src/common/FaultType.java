package common;

/**
 * Enumeration representing different types of faults that can occur in the drone system.
 * Used in Iteration 4 for fault handling and detection.
 */
public enum FaultType {
    NONE,           // No fault
    DRONE_STUCK,    // Drone stuck mid-flight (temporary fault - recoverable)
    NOZZLE_JAM,     // Nozzle jammed (hard fault - permanent)
    PACKET_LOSS     // Network packet loss (communication fault)
}
