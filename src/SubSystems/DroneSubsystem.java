package SubSystems;

import MessageTransport.MessageTransporter;
import common.*;

import java.io.IOException;
import java.net.*;
import java.util.Arrays;

public class DroneSubsystem implements Runnable {
    private static final String SCHEDULER_HOST = "localhost";
    private static final int SCHEDULER_PORT = 6000;
    private static final int DEFAULT_AGENT_CAPACITY = 100;
    private static final int DEFAULT_BATTERY_CAPACITY = 100;
    private static final int BATTERY_DRAIN_PER_TRAVEL_STEP = 1;
    private static final int BATTERY_DRAIN_DROPPING = 1;
    private static final int TIME_TO_OPEN_DOOR = 200; // milliseconds
    private static final double WATER_DROP_RATE = 0.2; // L/s
    private static final int ACCELERATION = 6; // m/s
    private static final int DECELERATION = 4; // m/s
    private static final int RESET_TIME_MS = 3000;
    private static final double SIMULATION_SPEED = 60.0;

    private final int droneId;
    private DroneState state = DroneState.IDLE;

    private final DatagramSocket socket;

    private Integer currentZoneId = null;
    private int remainingAgent = DEFAULT_AGENT_CAPACITY;
    private int batteryLevel = DEFAULT_BATTERY_CAPACITY;
    private int missionsCompleted = 0;

    private SystemCounts counts = null;

    public DroneSubsystem(int droneId) {
        this(droneId, null);
    }

    public DroneSubsystem(int droneId, MessageTransporter transport) {
        this.droneId = droneId;
        try {
            this.socket = new DatagramSocket(6100 + droneId);
            socket.setSoTimeout(200);
        } catch (SocketException e) {
            throw new RuntimeException(e);
        }
    }

    public DroneSubsystem(int droneId, MessageTransporter transport, SystemCounts counts) {
        this.droneId = droneId;
        try {
            this.socket = new DatagramSocket(6100 + droneId);
            socket.setSoTimeout(200);
        } catch (SocketException e) {
            throw new RuntimeException(e);
        }
        this.counts = counts;
    }

    @Override
    public void run() {
        if (common.DebugOutputFilter.isDroneOutputActive())
            System.out.println("[DRONE " + droneId + "] Drone subsystem started");

        try {
            while (!Thread.currentThread().isInterrupted()) {
                if (state == DroneState.OFFLINE) {
                    Thread.sleep(1000);
                    continue;
                }

                sendPoll();

                try {
                    byte[] buf = new byte[2048];
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    socket.receive(packet);

                    Message msg = Message.fromBytes(
                            Arrays.copyOf(packet.getData(), packet.getLength())
                    );

                    handleMessage(msg);
                } catch (SocketTimeoutException e) {
                    // no message this cycle
                }

                Thread.sleep((long) (500 / SIMULATION_SPEED));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        if (common.DebugOutputFilter.isDroneOutputActive())
            System.out.println("[DRONE " + droneId + "] Drone subsystem stopped");
    }

    /*
        Drone sends packet to scheduler to poll next task
     */
    private void sendPoll() throws IOException {
        if (state == DroneState.OFFLINE) return;

        byte[] data = Message.dronePoll(droneId).toBytes();
        DatagramPacket packet = new DatagramPacket(
                data, data.length, InetAddress.getByName(SCHEDULER_HOST), SCHEDULER_PORT
        );
        socket.send(packet);
        if (common.DebugOutputFilter.isDroneOutputActive())
            System.out.println("[DRONE " + droneId + "] Polling scheduler for tasks");
    }

    /*
        Drone sending status updates to scheduler
     */
    private void sendStatus(DroneState state, Integer zoneId, Integer remainingAgent) throws IOException {
        DroneStatus status = new DroneStatus(droneId, state, zoneId, remainingAgent, batteryLevel);
        Message msg = Message.droneStatus(droneId, status);
        byte[] out = msg.toBytes();

        DatagramPacket packet = new DatagramPacket(
                out, out.length, InetAddress.getByName(SCHEDULER_HOST), SCHEDULER_PORT
        );
        socket.send(packet);
        if (common.DebugOutputFilter.isDroneOutputActive())
            System.out.println("[DRONE " + droneId + "] Sent status: " + status);
    }

    private void sendStatusWithPosition(DroneState state,
                                        Integer zoneId,
                                        Integer remainingAgent,
                                        double posX,
                                        double posY) throws IOException {
        DroneStatus status = new DroneStatus(droneId, state, zoneId, remainingAgent, batteryLevel, posX, posY);
        Message msg = Message.droneStatus(droneId, status);
        byte[] out = msg.toBytes();

        DatagramPacket packet = new DatagramPacket(
                out, out.length, InetAddress.getByName(SCHEDULER_HOST), SCHEDULER_PORT
        );
        socket.send(packet);
        if (common.DebugOutputFilter.isDroneOutputActive())
            System.out.println("[DRONE " + droneId + "] Sent status with pos ("
                    + String.format("%.0f", posX) + "," + String.format("%.0f", posY) + "): " + status);
    }

    /*
        Drone done status update to scheduler
     */
    private void sendDone() throws IOException {
        DroneStatus status = new DroneStatus(
                droneId,
                state,
                currentZoneId,
                remainingAgent,
                batteryLevel,
                0,
                0
        );
        Message doneMsg = Message.droneDone(droneId, status);
        byte[] out = doneMsg.toBytes();

        DatagramPacket packet = new DatagramPacket(
                out, out.length, InetAddress.getByName(SCHEDULER_HOST), SCHEDULER_PORT
        );
        socket.send(packet);
    }

    /*
        Drone sends fault status to scheduler
     */
    private void sendFault(FaultType faultType, int zoneId, boolean recoverable) throws IOException {
        DroneFault fault = new DroneFault(droneId, zoneId, faultType, recoverable);
        Message msg = Message.droneFault(droneId, fault);
        byte[] out = msg.toBytes();

        DatagramPacket packet = new DatagramPacket(
                out, out.length, InetAddress.getByName(SCHEDULER_HOST), SCHEDULER_PORT
        );
        socket.send(packet);

        if (common.DebugOutputFilter.isDroneOutputActive())
            System.out.println("[DRONE " + droneId + "] Reported fault: " + fault);
    }

    /*
        method to help drone receive messages from scheduler
        depending on message type, perform action accordingly
     */
    private void handleMessage(Message msg) throws InterruptedException, IOException {
        switch (msg.getType()) {
            case DRONE_TASK -> {
                DroneCommand command = (DroneCommand) msg.getPayload();
                if (common.DebugOutputFilter.isDroneOutputActive())
                    System.out.println("[DRONE " + droneId + "] Received task: " + command);

                if (counts != null) {
                    counts.incBusyDrones();
                }

                boolean success = executeCommand(command);
                if (success) {
                    sendDone();
                    missionsCompleted++;
                }

                if (counts != null) {
                    counts.decBusyDrones();
                }
            }
            default -> {
                // ignore
            }
        }
    }

    /*
        Drone simulation method with calculations using values from iteration 0
     */
    private boolean executeCommand(DroneCommand command) throws InterruptedException, IOException {
        currentZoneId = command.get_zone_id();
        int amountUsed = command.getAgentAmount();

        transition(DroneEvent.TASK_RECEIVED);

        long outboundMs = computeTravelTimeMs(ZoneMap.distanceFromBase(currentZoneId));
        long returnMs = computeTravelTimeMs(ZoneMap.distanceFromBase(currentZoneId));
        long dropMs = computeDropTimeMs(amountUsed);

        if (common.DebugOutputFilter.isDroneOutputActive())
            System.out.println("[DRONE " + droneId + "] Dispatching to zone " + currentZoneId);

        boolean reachedZone = travelToZone(currentZoneId, outboundMs, command);
        if (!reachedZone) {
            return false;
        }

        transition(DroneEvent.ARRIVED);
        int[] zoneCoords = ZoneMap.get(currentZoneId);
        sendStatusWithPosition(state, currentZoneId, remainingAgent, zoneCoords[0], zoneCoords[1]);
        if (common.DebugOutputFilter.isDroneOutputActive())
            System.out.println("[DRONE " + droneId + "] Arrived at zone " + currentZoneId);

        if (command.getFaultType() == FaultType.NOZZLE_JAM) {
            if (common.DebugOutputFilter.isDroneOutputActive())
                System.out.println("[DRONE " + droneId + "] Nozzle jam fault detected");
            transition(DroneEvent.FAULT_DETECTED);
            sendFault(FaultType.NOZZLE_JAM, currentZoneId, false);

            transition(DroneEvent.HARD_FAULT);
            sendStatusWithPosition(state, currentZoneId, remainingAgent, zoneCoords[0], zoneCoords[1]);

            // return to base visually, then remain offline
            travelToBase(returnMs, DroneState.OFFLINE);
            currentZoneId = null;
            sendStatusWithPosition(DroneState.OFFLINE, null, remainingAgent, 0, 0);
            state = DroneState.OFFLINE;
            return false;
        }

        if (common.DebugOutputFilter.isDroneOutputActive())
            System.out.println("[DRONE " + droneId + "] Door opened");
        Thread.sleep((long) (TIME_TO_OPEN_DOOR / SIMULATION_SPEED));

        if (common.DebugOutputFilter.isDroneOutputActive())
            System.out.println("[DRONE " + droneId + "] Dropping agent (" + command.getSeverity() + ")");

        long droppingSteps = Math.max(1, dropMs / 200);
        long dropStepMs = Math.max(1, dropMs / droppingSteps);
        int agentPerStep = (int) Math.ceil((double) amountUsed / droppingSteps);

        for (int i = 0; i < droppingSteps; i++) {
            Thread.sleep(dropStepMs);
            batteryLevel = Math.max(0, batteryLevel - BATTERY_DRAIN_DROPPING);
            remainingAgent = Math.max(0, remainingAgent - agentPerStep);
            sendStatusWithPosition(state, currentZoneId, remainingAgent, zoneCoords[0], zoneCoords[1]);
        }

        transition(DroneEvent.DROP_COMPLETE);
        sendStatus(state, currentZoneId, remainingAgent);

        if (common.DebugOutputFilter.isDroneOutputActive())
            System.out.println("[DRONE " + droneId + "] Returning to base");
        travelToBase(returnMs, state);

        transition(DroneEvent.RETURN_COMPLETE);
        currentZoneId = null;

        remainingAgent = DEFAULT_AGENT_CAPACITY;
        batteryLevel = DEFAULT_BATTERY_CAPACITY;
        state = DroneState.IDLE;
        sendStatusWithPosition(state, null, remainingAgent, 0, 0);
        if (common.DebugOutputFilter.isDroneOutputActive())
            System.out.println("[DRONE " + droneId + "] Arrived at base. Refilled to: Agent=" + remainingAgent + "L, Battery=" + batteryLevel + "%");

        return true;
    }

    /*
        drone movement helper
     */
    private boolean travelToZone(int zoneId, long totalMs, DroneCommand command)
            throws InterruptedException, IOException {

        int[] dest = ZoneMap.get(zoneId);
        long steps = 25;
        long stepMs = Math.max(1, totalMs / steps);

        long faultDelayMs = (long) command.getFaultDelaySeconds() * 1000L;
        long elapsedMs = 0;
        boolean faultInjected = false;

        for (int i = 1; i <= steps; i++) {
            Thread.sleep(stepMs);
            elapsedMs += stepMs;

            double progress = (double) i / steps;
            double currX = dest[0] * progress;
            double currY = dest[1] * progress;

            batteryLevel = Math.max(0, batteryLevel - BATTERY_DRAIN_PER_TRAVEL_STEP);

            sendStatusWithPosition(state, zoneId, remainingAgent, currX, currY);

            if (!faultInjected
                    && command.getFaultType() == FaultType.DRONE_STUCK
                    && elapsedMs >= faultDelayMs) {

                if (common.DebugOutputFilter.isDroneOutputActive())
                    System.out.println("[DRONE " + droneId + "] Drone stuck fault detected");

                transition(DroneEvent.FAULT_DETECTED);
                sendStatusWithPosition(state, zoneId, remainingAgent, currX, currY);
                sendFault(FaultType.DRONE_STUCK, zoneId, true);

                Thread.sleep((long) (RESET_TIME_MS / SIMULATION_SPEED));
                transition(DroneEvent.RECOVERED);

                state = DroneState.RETURNING;
                sendStatusWithPosition(state, zoneId, remainingAgent, currX, currY);

                long remainingReturnMs = computeTravelTimeMs(Math.hypot(currX, currY));
                travelToBaseFrom(currX, currY, remainingReturnMs, DroneState.RETURNING);

                currentZoneId = null;
                batteryLevel = DEFAULT_BATTERY_CAPACITY;
                state = DroneState.IDLE;
                sendStatusWithPosition(state, null, remainingAgent, 0, 0);
                faultInjected = true;
                return false;
            }
        }

        return true;
    }

    /*
        RTB method when drone finishes
     */
    private void travelToBase(long totalMs, DroneState reportState) throws InterruptedException, IOException {
        int[] src = currentZoneId != null ? ZoneMap.get(currentZoneId) : new int[]{0, 0};
        travelToBaseFrom(src[0], src[1], totalMs, reportState);
    }

    /*
        RTB calculation helper method
     */
    private void travelToBaseFrom(double startX,
                                  double startY,
                                  long totalMs,
                                  DroneState reportState) throws InterruptedException, IOException {
        long steps = 25;
        long stepMs = Math.max(1, totalMs / steps);

        for (int i = 1; i <= steps; i++) {
            Thread.sleep(stepMs);
            double progress = (double) i / steps;
            double currentX = startX * (1.0 - progress);
            double currentY = startY * (1.0 - progress);

            batteryLevel = Math.max(0, batteryLevel - BATTERY_DRAIN_PER_TRAVEL_STEP);

            sendStatusWithPosition(reportState, null, remainingAgent, currentX, currentY);
        }
    }

    /*
        drop time calculation
     */
    private long computeDropTimeMs(int litresToDrop) {
        double seconds = litresToDrop / WATER_DROP_RATE;
        return (long) Math.ceil((seconds * 1000) / SIMULATION_SPEED);
    }

    /*
        travel time calculation
     */
    private long computeTravelTimeMs(double distanceMeters) {
        if (distanceMeters <= 0) return 1;

        double peakSpeed = Math.sqrt((2 * distanceMeters * ACCELERATION * DECELERATION) / (ACCELERATION + DECELERATION));
        double timeAccelerate = peakSpeed / ACCELERATION;
        double timeDecelerate = peakSpeed / DECELERATION;
        return Math.max(1, (long) Math.ceil((timeAccelerate + timeDecelerate) * 1000 / SIMULATION_SPEED));
    }

    /*
        state transition helper method
     */
    private void transition(DroneEvent event) {
        DroneState before = state;
        state = state.next(event);
        if (common.DebugOutputFilter.isDroneOutputActive())
            System.out.println("[DRONE " + droneId + "] State: " + before + " -> " + state + " on " + event);
    }

    public static void main(String[] args) {
        int droneId = 1;

        if (args.length > 0) {
            droneId = Integer.parseInt(args[0]);
        }

        DroneSubsystem drone = new DroneSubsystem(droneId);
        drone.run();
    }
}