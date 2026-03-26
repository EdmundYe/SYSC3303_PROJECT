package common;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

//Represents one incident/event row (from the input file) that goes through the system.
public final class FireEvent implements Serializable {
    private final Instant timestamp;
    private final int zoneId;
    private final FireEventType eventType;
    private final Severity severity;
    private final FaultType faultType;
    private final int faultDelaySeconds;

    public FireEvent(Instant timestamp, int zoneId, FireEventType eventType,
                     Severity severity, FaultType faultType, int faultDelaySeconds) {
        this.timestamp = Objects.requireNonNull(timestamp);
        this.zoneId = zoneId;
        this.eventType = Objects.requireNonNull(eventType);
        this.severity = Objects.requireNonNull(severity);
        this.faultType = faultType != null ? faultType : FaultType.NONE;
        this.faultDelaySeconds = faultDelaySeconds;
    }

    public Instant getTimestamp() { return timestamp; }
    public int getZoneId() { return zoneId; }
    public FireEventType getEventType() { return eventType; }
    public Severity getSeverity() { return severity; }
    public FaultType getFaultType() { return faultType; }
    public int getFaultDelaySeconds() { return faultDelaySeconds; }

    @Override
    public String toString() {
        return "FireEvent={" +
                "timestamp=" + timestamp +
                ", zoneId=" + zoneId +
                ", eventType=" + eventType +
                ", severity=" + severity +
                ", fault=" + faultType +
                ", faultDelay=" + faultDelaySeconds +
                '}';
    }
}