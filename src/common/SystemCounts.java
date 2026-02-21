package common;

import java.util.concurrent.atomic.AtomicInteger;

//class to track values for GUI
public final class SystemCounts {
    private final AtomicInteger activeFires = new AtomicInteger(0);
    private final AtomicInteger totalDrones = new AtomicInteger(0);
    private final AtomicInteger busyDrones = new AtomicInteger(0);

    // Fires
    public int incActiveFires() { return activeFires.incrementAndGet(); }
    public int decActiveFires() { return activeFires.decrementAndGet(); }
    public int getActiveFires() { return activeFires.get(); }

    // Drones
    public void setTotalDrones(int n) { totalDrones.set(Math.max(0, n)); }
    public int getTotalDrones() { return totalDrones.get(); }

    public int incBusyDrones() { return busyDrones.incrementAndGet(); }
    public int decBusyDrones() { return busyDrones.decrementAndGet(); }
    public int getBusyDrones() { return busyDrones.get(); }
}