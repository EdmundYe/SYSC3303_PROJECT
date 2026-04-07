package common;

/*
    Filter outputs for specific subsystems for targeted debug
 */
public final class DebugOutputFilter {
    private static final boolean allActive = true; // TODO: Make sure this is true for the final submission

    private static final boolean zoneMapOutputActive = false;
    private static final boolean schedulerOutputActive = false;
    private static final boolean droneOutputActive = false;
    private static final boolean fireIncidentOutputActive = false;
    private static final boolean guiOutputActive = false;

    public static boolean isZoneMapDebugActive() {
        return zoneMapOutputActive || allActive;
    }

    public static boolean isSchedulerOutputActive() {
        return schedulerOutputActive || allActive;
    }

    public static boolean isDroneOutputActive() {
        return droneOutputActive || allActive;
    }

    public static boolean isFireIncidentOutputActive() {
        return fireIncidentOutputActive || allActive;
    }

    public static boolean isGUIOutputActive() {
        return guiOutputActive || allActive;
    }
}