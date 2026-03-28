package common;

import java.io.Serializable;
import java.util.Objects;

public final class DroneFault implements Serializable {
    private final int droneId;
    private final int zoneId;
    private final FaultType faultType;
    private final boolean recoverable;
    private final long faultTimeMs;

    public DroneFault(int droneId, int zoneId, FaultType faultType, boolean recoverable) {
        this(droneId, zoneId, faultType, recoverable, System.currentTimeMillis());
    }

    public DroneFault(int droneId, int zoneId, FaultType faultType, boolean recoverable, long faultTimeMs) {
        this.droneId = droneId;
        this.zoneId = zoneId;
        this.faultType = Objects.requireNonNull(faultType);
        this.recoverable = recoverable;
        this.faultTimeMs = faultTimeMs;
    }

    public int getDroneId() {
        return droneId;
    }

    public int getZoneId() {
        return zoneId;
    }

    public FaultType getFaultType() {
        return faultType;
    }

    public boolean isRecoverable() {
        return recoverable;
    }

    public long getFaultTimeMs() {
        return faultTimeMs;
    }

    @Override
    public String toString() {
        return "DroneFault{" +
                "droneId=" + droneId +
                ", zoneId=" + zoneId +
                ", faultType=" + faultType +
                ", recoverable=" + recoverable +
                ", faultTimeMs=" + faultTimeMs +
                '}';
    }
}