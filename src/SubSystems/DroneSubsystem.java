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
    private static final int TIME_TO_OPEN_DOOR = 200; // milliseconds
    private static final double WATER_DROP_RATE = 0.2; // L/s
    private static final int ACCELERATION = 6; // m/s
    private static final int DECELERATION = 4; // m/s
    private static final int RESET_TIME_MS = 3000;

    private final int droneId;
    private DroneState state = DroneState.IDLE;

    private final DatagramSocket socket;

    private Integer currentZoneId = null;
    private int remainingAgent = DEFAULT_AGENT_CAPACITY;
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

                Thread.sleep(500);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        System.out.println("[DRONE " + droneId + "] Drone subsystem stopped");
    }

    private void sendPoll() throws IOException {
        if (state == DroneState.OFFLINE) return;

        byte[] data = Message.dronePoll(droneId).toBytes();
        DatagramPacket packet = new DatagramPacket(
                data, data.length, InetAddress.getByName(SCHEDULER_HOST), SCHEDULER_PORT
        );
        socket.send(packet);
        System.out.println("[DRONE " + droneId + "] Polling scheduler for tasks");
    }

    private void sendStatus(DroneState state, Integer zoneId, Integer remainingAgent) throws IOException {
        DroneStatus status = new DroneStatus(droneId, state, zoneId, remainingAgent);
        Message msg = Message.droneStatus(droneId, status);
        byte[] out = msg.toBytes();

        DatagramPacket packet = new DatagramPacket(
                out, out.length, InetAddress.getByName(SCHEDULER_HOST), SCHEDULER_PORT
        );
        socket.send(packet);
        System.out.println("[DRONE " + droneId + "] Sent status: " + status);
    }

    private void sendStatusWithPosition(DroneState state,
                                        Integer zoneId,
                                        Integer remainingAgent,
                                        double posX,
                                        double posY) throws IOException {
        DroneStatus status = new DroneStatus(droneId, state, zoneId, remainingAgent, posX, posY);
        Message msg = Message.droneStatus(droneId, status);
        byte[] out = msg.toBytes();

        DatagramPacket packet = new DatagramPacket(
                out, out.length, InetAddress.getByName(SCHEDULER_HOST), SCHEDULER_PORT
        );
        socket.send(packet);
        System.out.println("[DRONE " + droneId + "] Sent status with pos ("
                + String.format("%.0f", posX) + "," + String.format("%.0f", posY) + "): " + status);
    }

    private void sendDone() throws IOException {
        DroneStatus status = new DroneStatus(droneId, state, null, remainingAgent, 0, 0);
        Message doneMsg = Message.droneDone(droneId, status);
        byte[] out = doneMsg.toBytes();

        DatagramPacket packet = new DatagramPacket(
                out, out.length, InetAddress.getByName(SCHEDULER_HOST), SCHEDULER_PORT
        );
        socket.send(packet);
        System.out.println("[DRONE " + droneId + "] Task completed and reported");
    }

    private void sendFault(FaultType faultType, int zoneId, boolean recoverable) throws IOException {
        DroneFault fault = new DroneFault(droneId, zoneId, faultType, recoverable);
        Message msg = Message.droneFault(droneId, fault);
        byte[] out = msg.toBytes();

        DatagramPacket packet = new DatagramPacket(
                out, out.length, InetAddress.getByName(SCHEDULER_HOST), SCHEDULER_PORT
        );
        socket.send(packet);

        System.out.println("[DRONE " + droneId + "] Reported fault: " + fault);
    }

    private void handleMessage(Message msg) throws InterruptedException, IOException {
        switch (msg.getType()) {
            case DRONE_TASK -> {
                DroneCommand command = (DroneCommand) msg.getPayload();
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

    private boolean executeCommand(DroneCommand command) throws InterruptedException, IOException {
        currentZoneId = command.get_zone_id();
        int amountUsed = agentUsageForSeverity(command.getSeverity());

        transition(DroneEvent.TASK_RECEIVED);

        long outboundMs = computeTravelTimeMs(ZoneMap.distanceFromBase(currentZoneId));
        long returnMs = computeTravelTimeMs(ZoneMap.distanceFromBase(currentZoneId));
        long dropMs = computeDropTimeMs(amountUsed);

        System.out.println("[DRONE " + droneId + "] Dispatching to zone " + currentZoneId);

        boolean reachedZone = travelToZone(currentZoneId, outboundMs, command);
        if (!reachedZone) {
            return false;
        }

        transition(DroneEvent.ARRIVED);
        int[] zoneCoords = ZoneMap.get(currentZoneId);
        sendStatusWithPosition(state, currentZoneId, remainingAgent, zoneCoords[0], zoneCoords[1]);
        System.out.println("[DRONE " + droneId + "] Arrived at zone " + currentZoneId);

        if (command.getFaultType() == FaultType.NOZZLE_JAM) {
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

        System.out.println("[DRONE " + droneId + "] Door opened");
        Thread.sleep(TIME_TO_OPEN_DOOR);

        System.out.println("[DRONE " + droneId + "] Dropping agent (" + command.getSeverity() + ")");
        Thread.sleep(dropMs);

        remainingAgent = Math.max(0, remainingAgent - amountUsed);
        sendStatusWithPosition(state, currentZoneId, remainingAgent, zoneCoords[0], zoneCoords[1]);

        transition(DroneEvent.DROP_COMPLETE);
        sendStatus(state, currentZoneId, remainingAgent);

        System.out.println("[DRONE " + droneId + "] Returning to base");
        travelToBase(returnMs, state);

        transition(DroneEvent.RETURN_COMPLETE);
        currentZoneId = null;

        state = DroneState.IDLE;
        sendStatusWithPosition(state, null, remainingAgent, 0, 0);
        System.out.println("[DRONE " + droneId + "] Arrived at base");

        return true;
    }

<<<<<<< HEAD
    private boolean travelToZone(int zoneId, long totalMs, DroneCommand command)
            throws InterruptedException, IOException {

=======
    /*
    Method for tracking drone movement

     */
    private void travelToZone(int zoneId, long totalMs) throws InterruptedException, IOException{
>>>>>>> b6d95c3233794da706f4d76a18ccb37075d077c3
        int[] dest = ZoneMap.get(zoneId);
        long steps = 10;
        long stepMs = Math.max(1, totalMs / steps);

        long faultDelayMs = (long) command.getFaultDelaySeconds() * 1000L;
        long elapsedMs = 0;
        boolean faultInjected = false;

        for (int i = 1; i <= steps; i++) {
            Thread.sleep(stepMs);
            elapsedMs += stepMs;

            try {
                byte[] buf = new byte[2048];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p);
                Message msg = Message.fromBytes(Arrays.copyOf(p.getData(), p.getLength()));

                if (msg.getType() == MessageType.DRONE_TASK) {
                    DroneCommand redirect = (DroneCommand) msg.getPayload();
                    System.out.println("[DRONE " + droneId + "] Redirected to zone " + redirect.get_zone_id());
                    currentZoneId = redirect.get_zone_id();
                    return travelToZone(currentZoneId,
                            computeTravelTimeMs(ZoneMap.distanceFromBase(currentZoneId)),
                            redirect);
                }
            } catch (SocketTimeoutException ignored) {
            }

            double progress = (double) i / steps;
            double currX = dest[0] * progress;
            double currY = dest[1] * progress;
            sendStatusWithPosition(state, zoneId, remainingAgent, currX, currY);

            if (!faultInjected
                    && command.getFaultType() == FaultType.DRONE_STUCK
                    && elapsedMs >= faultDelayMs) {

                System.out.println("[DRONE " + droneId + "] Drone stuck fault detected");
                transition(DroneEvent.FAULT_DETECTED);
                sendStatusWithPosition(state, zoneId, remainingAgent, currX, currY);
                sendFault(FaultType.DRONE_STUCK, zoneId, true);

                Thread.sleep(RESET_TIME_MS);
                transition(DroneEvent.RECOVERED);

                state = DroneState.RETURNING;
                sendStatusWithPosition(state, zoneId, remainingAgent, currX, currY);

                long remainingReturnMs = computeTravelTimeMs(Math.hypot(currX, currY));
                travelToBaseFrom(currX, currY, remainingReturnMs, DroneState.RETURNING);

                currentZoneId = null;
                state = DroneState.IDLE;
                sendStatusWithPosition(state, null, remainingAgent, 0, 0);
                faultInjected = true;
                return false;
            }
        }

        return true;
    }

    private void travelToBase(long totalMs, DroneState reportState) throws InterruptedException, IOException {
        int[] src = currentZoneId != null ? ZoneMap.get(currentZoneId) : new int[]{0, 0};
        travelToBaseFrom(src[0], src[1], totalMs, reportState);
    }

    private void travelToBaseFrom(double startX,
                                  double startY,
                                  long totalMs,
                                  DroneState reportState) throws InterruptedException, IOException {
        long steps = 10;
        long stepMs = Math.max(1, totalMs / steps);

        for (int i = 1; i <= steps; i++) {
            Thread.sleep(stepMs);
            double progress = (double) i / steps;
            double currentX = startX * (1.0 - progress);
            double currentY = startY * (1.0 - progress);
            sendStatusWithPosition(reportState, null, remainingAgent, currentX, currentY);
        }
    }

    private long computeDropTimeMs(int litresToDrop) {
        double seconds = litresToDrop / WATER_DROP_RATE;
        return (long) Math.ceil(seconds * 1000);
    }

    private long computeTravelTimeMs(double distanceMeters) {
        if (distanceMeters <= 0) return 1;

        double peakSpeed = Math.sqrt((2 * distanceMeters * ACCELERATION * DECELERATION) / (ACCELERATION + DECELERATION));
        double timeAccelerate = peakSpeed / ACCELERATION;
        double timeDecelerate = peakSpeed / DECELERATION;
        return Math.max(1, (long) Math.ceil((timeAccelerate + timeDecelerate) * 1000));
    }

    private int agentUsageForSeverity(Severity severity) {
        return switch (severity) {
            case LOW -> 10;
            case MODERATE -> 20;
            case HIGH -> 30;
        };
    }

    private void transition(DroneEvent event) {
        DroneState before = state;
        state = state.next(event);
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