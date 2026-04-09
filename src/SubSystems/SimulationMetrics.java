package SubSystems;

import common.DroneState;
import common.DroneStatus;
import common.FireEvent;
import common.Severity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Collects and computes performance metrics for the fire‑response simulation.
 */
public class SimulationMetrics {

    // Keep this aligned with the simulation speed used by your drone subsystem.
    private static final double DRONE_SIMULATION_SPEED = 60.0;

    private static final class EventMetric {
        final String key;
        final int zoneId;
        final Severity severity;
        final long createdAtMs;

        Long firstArrivalAtMs;
        Long completedAtMs;

        /**
         * Tracks timing information for a single fire event
         * */
        EventMetric(String key, int zoneId, Severity severity, long createdAtMs) {
            this.key = key;
            this.zoneId = zoneId;
            this.severity = severity;
            this.createdAtMs = createdAtMs;
        }

        /**
         * Returns the wall‑clock time from event creation to first drone arrival.
         *
         * @return response time in ms, or -1 if no arrival recorded
         */
        long responseTimeWallMs() {
            if (firstArrivalAtMs == null) return -1L;
            return Math.max(0L, firstArrivalAtMs - createdAtMs);
        }

        /**
         * Returns the wall‑clock time from event creation to completion.
         *
         * @return completion time in ms, or -1 if not completed
         */
        long completionTimeWallMs() {
            if (completedAtMs == null) return -1L;
            return Math.max(0L, completedAtMs - createdAtMs);
        }

        /**
         * Returns the simulated response time (scaled by simulation speed).
         */
        double responseTimeSimMs() {
            long v = responseTimeWallMs();
            return v < 0 ? -1.0 : v * DRONE_SIMULATION_SPEED;
        }

        /**
         * Returns the simulated completion time (scaled by simulation speed).
         */
        double completionTimeSimMs() {
            long v = completionTimeWallMs();
            return v < 0 ? -1.0 : v * DRONE_SIMULATION_SPEED;
        }
    }

    /**
     * Tracks per‑drone metrics
     */
    private static final class DroneMetric {
        final int droneId;
        final EnumMap<DroneState, Long> stateTimeMs = new EnumMap<>(DroneState.class);

        DroneState lastState = DroneState.IDLE;
        long lastStateChangeMs = System.currentTimeMillis();

        Double lastX = null;
        Double lastY = null;
        double totalDistanceMeters = 0.0;

        int completedMissions = 0;
        int faultCount = 0;

        DroneMetric(int droneId) {
            this.droneId = droneId;
            for (DroneState state : DroneState.values()) {
                stateTimeMs.put(state, 0L);
            }
        }

        /**
         * Records a transition from the previous state to a new state and updates
         * accumulated wall‑clock time spent in the old state.
         *
         * @param newState the new drone state
         * @param nowMs    timestamp of the transition
         */
        void recordStateTransition(DroneState newState, long nowMs) {
            long delta = Math.max(0L, nowMs - lastStateChangeMs);
            stateTimeMs.put(lastState, stateTimeMs.getOrDefault(lastState, 0L) + delta);
            lastState = newState;
            lastStateChangeMs = nowMs;
        }

        /**
         * Updates the drone's tracked position and accumulates distance traveled.
         *
         * @param x new X coordinate
         * @param y new Y coordinate
         */
        void recordPosition(double x, double y) {
            if (lastX != null && lastY != null) {
                totalDistanceMeters += Math.hypot(x - lastX, y - lastY);
            }
            lastX = x;
            lastY = y;
        }

        /**
         * Returns wall‑clock time spent in the given state.
         */
        long getStateTimeWallMs(DroneState state) {
            return stateTimeMs.getOrDefault(state, 0L);
        }

        /**
         * Returns simulated time spent in the given state.
         */
        double getStateTimeSimMs(DroneState state) {
            return getStateTimeWallMs(state) * DRONE_SIMULATION_SPEED;
        }

        /**
         * Returns total simulated time spent in active states (EN_ROUTE, DROPPING, RETURNING).
         */
        double getActiveTimeSimMs() {
            return getStateTimeSimMs(DroneState.EN_ROUTE)
                    + getStateTimeSimMs(DroneState.DROPPING)
                    + getStateTimeSimMs(DroneState.RETURNING);
        }

        /**
         * Returns total simulated idle time.
         */
        double getIdleTimeSimMs() {
            return getStateTimeSimMs(DroneState.IDLE);
        }

        /**
         * Returns total simulated time tracked across all states.
         */
        double getTrackedTimeSimMs() {
            double total = 0.0;
            for (DroneState state : DroneState.values()) {
                total += getStateTimeSimMs(state);
            }
            return total;
        }

        /**
         * Returns drone utilization as a percentage of active time vs. total tracked time.
         */
        double getUtilizationPercent() {
            double tracked = getTrackedTimeSimMs();
            if (tracked <= 0.0) return 0.0;
            return (getActiveTimeSimMs() / tracked) * 100.0;
        }
    }

    private final Map<String, EventMetric> eventsByKey = new HashMap<>();
    private final Map<Integer, DroneMetric> drones = new HashMap<>();
    private final Map<Integer, String> activeEventKeyByZone = new HashMap<>();

    private long simulationStartMs = System.currentTimeMillis();
    private long firstEventDetectedMs = -1L;
    private long lastFireOutMs = -1L;

    private boolean finalReportPrinted = false;

    /**
     * Ensures a DroneMetric entry exists for the given drone ID.
     *
     * @param droneId the drone to register
     */
    public synchronized void registerDrone(int droneId) {
        drones.computeIfAbsent(droneId, DroneMetric::new);
    }

    /**
     * Registers a contiguous range of drone IDs from 1 to totalDrones.
     *
     * @param totalDrones number of drones to register
     */
    public synchronized void registerDroneRange(int totalDrones) {
        for (int i = 1; i <= totalDrones; i++) {
            registerDrone(i);
        }
    }

    /**
     * Records the detection of a new fire event, creating a new EventMetric and
     * marking the zone as active. Also records the timestamp of the first event
     * detected in the simulation.
     *
     * @param event the detected fire event
     */
    public synchronized void recordFireDetected(FireEvent event) {
        long now = System.currentTimeMillis();

        if (firstEventDetectedMs < 0) {
            firstEventDetectedMs = now;
        }

        String key = buildEventKey(event);
        EventMetric metric = new EventMetric(key, event.getZoneId(), event.getSeverity(), now);
        eventsByKey.put(key, metric);
        activeEventKeyByZone.put(event.getZoneId(), key);
    }

    /**
     * Records completion of a fire event. Updates the corresponding EventMetric
     * with a completion timestamp and clears the zone from active tracking.
     *
     * @param event the extinguished fire event
     */
    public synchronized void recordFireOut(FireEvent event) {
        long now = System.currentTimeMillis();
        String key = buildEventKey(event);
        EventMetric metric = eventsByKey.get(key);

        if (metric != null) {
            metric.completedAtMs = now;
        } else {
            metric = activeEventByZone(event.getZoneId());
            if (metric != null) {
                metric.completedAtMs = now;
            }
        }

        activeEventKeyByZone.remove(event.getZoneId());
        lastFireOutMs = now;
    }

    /**
     * Updates metrics based on a drone's latest status
     * @param status the drone's reported status
     */
    public synchronized void recordDroneStatus(DroneStatus status) {
        DroneMetric dm = drones.computeIfAbsent(status.get_drone_id(), DroneMetric::new);
        long now = status.get_status_time_ms();

        if (status.getState() != dm.lastState) {
            dm.recordStateTransition(status.getState(), now);
        }

        dm.recordPosition(status.getPosX(), status.getPosY());

        // "first arrives to service it" is best approximated here as the first DROPPING state
        // for the currently active event in that zone.
        Integer zoneId = status.get_zone_id();
        if (zoneId != null && status.getState() == DroneState.DROPPING) {
            EventMetric ev = activeEventByZone(zoneId);
            if (ev != null && ev.firstArrivalAtMs == null) {
                ev.firstArrivalAtMs = now;
            }
        }
    }

    /**
     * Records that a drone has successfully completed a mission. Increments the
     * drone's completed‑mission counter. Creates a DroneMetric entry if needed.
     *
     * @param droneId the drone that completed a mission
     */
    public synchronized void recordDroneDone(int droneId) {
        DroneMetric dm = drones.computeIfAbsent(droneId, DroneMetric::new);
        dm.completedMissions++;
    }

    /**
     * Records that a drone has reported a fault. Increments the drone's fault
     * counter. Creates a DroneMetric entry if needed.
     *
     * @param droneId the drone that experienced a fault
     */
    public synchronized void recordDroneFault(int droneId) {
        DroneMetric dm = drones.computeIfAbsent(droneId, DroneMetric::new);
        dm.faultCount++;
    }

    /**
     * Finalizes state‑time accounting for all drones by closing out the time
     * spent in their current state up to the present moment. This is called
     * before generating the final report to ensure all durations are accurate.
     */
    public synchronized void flushOpenStateTimes() {
        long now = System.currentTimeMillis();
        for (DroneMetric dm : drones.values()) {
            long delta = Math.max(0L, now - dm.lastStateChangeMs);
            dm.stateTimeMs.put(dm.lastState, dm.stateTimeMs.getOrDefault(dm.lastState, 0L) + delta);
            dm.lastStateChangeMs = now;
        }
    }

    /**
     * Determines whether the simulation has reached a terminal state and the
     * final metrics report should be printed.
     * @param noPendingEvents true if the scheduler has no queued events
     * @param noBusyDrones    true if all drones are idle or offline
     * @param activeFires     number of active fires remaining
     * @return true if the final report should be printed
     */
    public synchronized boolean shouldPrintFinalReport(boolean noPendingEvents, boolean noBusyDrones, int activeFires) {
        return !finalReportPrinted
                && firstEventDetectedMs >= 0
                && noPendingEvents
                && noBusyDrones
                && activeFires == 0;
    }

    /**
     * Generates and prints the final simulation metrics report. Ensures the
     * report is printed only once, flushes all open state times, builds the
     * report lines, prints them to stdout, and writes them to a file.
     */
    public synchronized void printFinalReport() {
        if (finalReportPrinted) return;
        finalReportPrinted = true;

        flushOpenStateTimes();

        List<String> lines = buildReportLines();
        for (String line : lines) {
            System.out.println(line);
        }
        writeReportToFile(lines);
    }

    /**
     * Constructs a human‑readable multi‑section metrics report summary
     * @return a list of formatted report lines
     */
    private List<String> buildReportLines() {
        List<String> lines = new ArrayList<>();

        lines.add("");
        lines.add("==================================================");
        lines.add("SIMULATION METRICS REPORT");
        lines.add("==================================================");

        double totalResponseSimMs = 0.0;
        double maxResponseSimMs = 0.0;
        int responseCount = 0;

        double totalCompletionSimMs = 0.0;
        double maxCompletionSimMs = 0.0;
        int completionCount = 0;

        for (EventMetric ev : eventsByKey.values()) {
            double responseSimMs = ev.responseTimeSimMs();
            if (responseSimMs >= 0.0) {
                totalResponseSimMs += responseSimMs;
                maxResponseSimMs = Math.max(maxResponseSimMs, responseSimMs);
                responseCount++;
            }

            double completionSimMs = ev.completionTimeSimMs();
            if (completionSimMs >= 0.0) {
                totalCompletionSimMs += completionSimMs;
                maxCompletionSimMs = Math.max(maxCompletionSimMs, completionSimMs);
                completionCount++;
            }
        }

        double avgResponseSec = responseCount == 0 ? 0.0 : (totalResponseSimMs / responseCount) / 1000.0;
        double maxResponseSec = maxResponseSimMs / 1000.0;

        double avgCompletionSec = completionCount == 0 ? 0.0 : (totalCompletionSimMs / completionCount) / 1000.0;
        double maxCompletionSec = maxCompletionSimMs / 1000.0;

        double wallClockRuntimeSec = (System.currentTimeMillis() - simulationStartMs) / 1000.0;
        double simulatedRunSec = (firstEventDetectedMs >= 0 && lastFireOutMs >= 0)
                ? ((lastFireOutMs - firstEventDetectedMs) * DRONE_SIMULATION_SPEED) / 1000.0
                : 0.0;

        lines.add(String.format("Simulation wall-clock runtime: %.2f s", wallClockRuntimeSec));
        lines.add(String.format("Time from first incident to last fire extinguished: %.2f s", simulatedRunSec));
        lines.add(String.format("Completed incidents: %d", completionCount));
        lines.add("");
        lines.add(String.format("Average Event Response Time: %.2f s", avgResponseSec));
        lines.add(String.format("Maximum Event Response Time: %.2f s", maxResponseSec));
        lines.add(String.format("Average Event Completion Time: %.2f s", avgCompletionSec));
        lines.add(String.format("Maximum Event Completion Time: %.2f s", maxCompletionSec));

        double fleetDistance = 0.0;
        double fleetIdleSimMs = 0.0;
        double fleetEnRouteSimMs = 0.0;
        double fleetDroppingSimMs = 0.0;
        double fleetReturningSimMs = 0.0;
        double fleetFaultedSimMs = 0.0;

        lines.add("");
        lines.add("Per-drone metrics:");
        for (int droneId : new TreeSet<>(drones.keySet())) {
            DroneMetric dm = drones.get(droneId);

            fleetDistance += dm.totalDistanceMeters;
            fleetIdleSimMs += dm.getStateTimeSimMs(DroneState.IDLE);
            fleetEnRouteSimMs += dm.getStateTimeSimMs(DroneState.EN_ROUTE);
            fleetDroppingSimMs += dm.getStateTimeSimMs(DroneState.DROPPING);
            fleetReturningSimMs += dm.getStateTimeSimMs(DroneState.RETURNING);
            fleetFaultedSimMs += dm.getStateTimeSimMs(DroneState.FAULTED);

            lines.add(String.format(
                    "Drone %d | distance=%.1f m | idle=%.2f s | en_route=%.2f s | dropping=%.2f s | returning=%.2f s | faulted=%.2f s | missions=%d | faults=%d | utilization=%.2f%%",
                    dm.droneId,
                    dm.totalDistanceMeters,
                    dm.getStateTimeSimMs(DroneState.IDLE) / 1000.0,
                    dm.getStateTimeSimMs(DroneState.EN_ROUTE) / 1000.0,
                    dm.getStateTimeSimMs(DroneState.DROPPING) / 1000.0,
                    dm.getStateTimeSimMs(DroneState.RETURNING) / 1000.0,
                    dm.getStateTimeSimMs(DroneState.FAULTED) / 1000.0,
                    dm.completedMissions,
                    dm.faultCount,
                    dm.getUtilizationPercent()
            ));
        }

        int droneCount = Math.max(1, drones.size());

        lines.add("");
        lines.add("Fleet averages:");
        lines.add(String.format("Average drone idle time: %.2f s", (fleetIdleSimMs / 1000.0) / droneCount));
        lines.add(String.format("Average drone en-route time: %.2f s", (fleetEnRouteSimMs / 1000.0) / droneCount));
        lines.add(String.format("Average drone dropping time: %.2f s", (fleetDroppingSimMs / 1000.0) / droneCount));
        lines.add(String.format("Average drone returning time: %.2f s", (fleetReturningSimMs / 1000.0) / droneCount));
        lines.add(String.format("Average drone faulted time: %.2f s", (fleetFaultedSimMs / 1000.0) / droneCount));
        lines.add(String.format("Total fleet distance travelled: %.1f m", fleetDistance));

        lines.add("==================================================");
        lines.add("");

        return lines;
    }

    /**
     * Writes the metrics report to a file named {@code metrics_report.txt}.
     * Overwrites any existing file. Logs success or failure to stdout.
     *
     * @param lines the report content to write
     */
    private void writeReportToFile(List<String> lines) {
        Path outputPath = Path.of("metrics_report.txt");
        try {
            Files.write(
                    outputPath,
                    lines,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
            System.out.println("[METRICS] Report written to " + outputPath.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("[METRICS] Failed to write metrics_report.txt: " + e.getMessage());
        }
    }

    /**
     * Returns the active EventMetric associated with the given zone, if any.
     *
     * @param zoneId the zone to look up
     * @return the active EventMetric, or null if none exists
     */
    private EventMetric activeEventByZone(int zoneId) {
        String key = activeEventKeyByZone.get(zoneId);
        if (key == null) return null;
        return eventsByKey.get(key);
    }

    /**
     * Builds a unique key for a fire event based on timestamp, zone, event type,
     * and severity. Used to correlate FIRE_EVENT and FIRE_OUT messages.
     *
     * @param event the fire event to key
     * @return a unique string key representing the event
     */
    private String buildEventKey(FireEvent event) {
        Instant ts = event.getTimestamp();
        long millis = ts != null ? ts.toEpochMilli() : -1L;
        return millis + "|" + event.getZoneId() + "|" + event.getEventType() + "|" + event.getSeverity();
    }
}