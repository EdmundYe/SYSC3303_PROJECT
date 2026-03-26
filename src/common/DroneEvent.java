package common;

public enum DroneEvent {
    TASK_RECEIVED,
    ARRIVED,
    DROP_COMPLETE,
    RETURN_COMPLETE,
    FAULT_DETECTED,     // A fault has been detected (DRONE_STUCK, NOZZLE_JAM, etc.)
    RECOVERED,          // Drone recovered from a temporary fault (DRONE_STUCK)
    HARD_FAULT          // Hard fault occurred - drone must go offline permanently (NOZZLE_JAM)
}

