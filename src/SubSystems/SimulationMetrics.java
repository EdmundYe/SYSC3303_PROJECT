package SubSystems;

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

public class SimulationMetrics {

    private static final class EventMetric {
        final int zoneId;
        final Severity severity;
        final long detectedAtMs;
        Long extinguishedAtMs;

        EventMetric(int zoneId, Severity severity, long detectedAtMs) {
            this.zoneId = zoneId;
            this.severity = severity;
            this.detectedAtMs = detectedAtMs;
        }

        long responseDurationMs() {
            if (extinguishedAtMs == null) return -1;
            return extinguishedAtMs - detectedAtMs;
        }
    }

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

        void recordStateTransition(DroneState newState, long nowMs) {
            long delta = Math.max(0, nowMs - lastStateChangeMs);
            stateTimeMs.put(lastState, stateTimeMs.getOrDefault(lastState, 0L) + delta);
            lastState = newState;
            lastStateChangeMs = nowMs;
        }

        void recordPosition(double x, double y) {
            if (lastX != null && lastY != null) {
                totalDistanceMeters += Math.hypot(x - lastX, y - lastY);
            }
            lastX = x;
            lastY = y;
        }

        long getStateTime(DroneState state) {
            return stateTimeMs.getOrDefault(state, 0L);
        }
    }

    private final Map<String, EventMetric> eventsByRequestId = new HashMap<>();
    private final Map<Integer, DroneMetric> drones = new HashMap<>();

    private long firstEventDetectedMs = -1;
    private long lastFireOutMs = -1;
    private final long simulationStartMs = System.currentTimeMillis();
    private boolean finalReportPrinted = false;

    public synchronized void recordFireDetected(FireEvent event) {
        long detectedAtMs = System.currentTimeMillis();

        if (firstEventDetectedMs < 0) {
            firstEventDetectedMs = detectedAtMs;
        }

        String key = buildEventKey(event);
        eventsByRequestId.put(key, new EventMetric(event.getZoneId(), event.getSeverity(), detectedAtMs));
    }

    public synchronized void recordFireOut(FireEvent event) {
        long now = System.currentTimeMillis();
        String key = buildEventKey(event);
        EventMetric metric = eventsByRequestId.get(key);
        if (metric != null) {
            metric.extinguishedAtMs = now;
        }
        lastFireOutMs = now;
    }

    public synchronized void recordDroneStatus(DroneStatus status) {
        DroneMetric dm = drones.computeIfAbsent(status.get_drone_id(), DroneMetric::new);
        long now = status.get_status_time_ms();

        if (status.getState() != dm.lastState) {
            dm.recordStateTransition(status.getState(), now);
        }

        dm.recordPosition(status.getPosX(), status.getPosY());
    }

    public synchronized void recordDroneDone(int droneId) {
        DroneMetric dm = drones.computeIfAbsent(droneId, DroneMetric::new);
        dm.completedMissions++;
    }

    public synchronized void recordDroneFault(int droneId) {
        DroneMetric dm = drones.computeIfAbsent(droneId, DroneMetric::new);
        dm.faultCount++;
    }

    public synchronized void flushOpenStateTimes() {
        long now = System.currentTimeMillis();
        for (DroneMetric dm : drones.values()) {
            dm.recordStateTransition(dm.lastState, now);
        }
    }

    public synchronized boolean shouldPrintFinalReport(boolean noPendingEvents, boolean noBusyDrones, int activeFires) {
        return !finalReportPrinted
                && firstEventDetectedMs >= 0
                && noPendingEvents
                && noBusyDrones
                && activeFires == 0;
    }

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

    private List<String> buildReportLines() {
        List<String> lines = new ArrayList<>();

        lines.add("");
        lines.add("==================================================");
        lines.add("SIMULATION METRICS REPORT");
        lines.add("==================================================");

        int completedEvents = 0;
        long totalResponseMs = 0;

        for (EventMetric ev : eventsByRequestId.values()) {
            if (ev.extinguishedAtMs != null) {
                completedEvents++;
                totalResponseMs += ev.responseDurationMs();
            }
        }

        double avgResponseSec = completedEvents == 0 ? 0.0 : (totalResponseMs / 1000.0) / completedEvents;
        double totalRunSec = (firstEventDetectedMs >= 0 && lastFireOutMs >= 0)
                ? (lastFireOutMs - firstEventDetectedMs) / 1000.0
                : 0.0;

        lines.add(String.format("Simulation wall-clock runtime: %.2f s", (System.currentTimeMillis() - simulationStartMs) / 1000.0));
        lines.add(String.format("Time from first incident to last fire extinguished: %.2f s", totalRunSec));
        lines.add(String.format("Completed incidents: %d", completedEvents));
        lines.add(String.format("Average incident detect-to-extinguish time: %.2f s", avgResponseSec));

        double fleetDistance = 0.0;
        long fleetIdleMs = 0L;
        long fleetEnRouteMs = 0L;
        long fleetDroppingMs = 0L;
        long fleetReturningMs = 0L;
        long fleetFaultedMs = 0L;

        lines.add("");
        lines.add("Per-drone metrics:");
        for (DroneMetric dm : drones.values()) {
            fleetDistance += dm.totalDistanceMeters;
            fleetIdleMs += dm.getStateTime(DroneState.IDLE);
            fleetEnRouteMs += dm.getStateTime(DroneState.EN_ROUTE);
            fleetDroppingMs += dm.getStateTime(DroneState.DROPPING);
            fleetReturningMs += dm.getStateTime(DroneState.RETURNING);
            fleetFaultedMs += dm.getStateTime(DroneState.FAULTED);

            lines.add(String.format(
                    "Drone %d | distance=%.1f m | idle=%.2f s | en_route=%.2f s | dropping=%.2f s | returning=%.2f s | faulted=%.2f s | missions=%d | faults=%d",
                    dm.droneId,
                    dm.totalDistanceMeters,
                    dm.getStateTime(DroneState.IDLE) / 1000.0,
                    dm.getStateTime(DroneState.EN_ROUTE) / 1000.0,
                    dm.getStateTime(DroneState.DROPPING) / 1000.0,
                    dm.getStateTime(DroneState.RETURNING) / 1000.0,
                    dm.getStateTime(DroneState.FAULTED) / 1000.0,
                    dm.completedMissions,
                    dm.faultCount
            ));
        }

        int droneCount = Math.max(1, drones.size());

        lines.add("");
        lines.add("Fleet averages:");
        lines.add(String.format("Average drone idle time: %.2f s", (fleetIdleMs / 1000.0) / droneCount));
        lines.add(String.format("Average drone en-route time: %.2f s", (fleetEnRouteMs / 1000.0) / droneCount));
        lines.add(String.format("Average drone dropping time: %.2f s", (fleetDroppingMs / 1000.0) / droneCount));
        lines.add(String.format("Average drone returning time: %.2f s", (fleetReturningMs / 1000.0) / droneCount));
        lines.add(String.format("Average drone faulted time: %.2f s", (fleetFaultedMs / 1000.0) / droneCount));
        lines.add(String.format("Total fleet distance travelled: %.1f m", fleetDistance));

        lines.add("==================================================");
        lines.add("");

        return lines;
    }

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

    private String buildEventKey(FireEvent event) {
        Instant ts = event.getTimestamp();
        long millis = ts != null ? ts.toEpochMilli() : -1L;
        return millis + "|" + event.getZoneId() + "|" + event.getEventType() + "|" + event.getSeverity();
    }
}
