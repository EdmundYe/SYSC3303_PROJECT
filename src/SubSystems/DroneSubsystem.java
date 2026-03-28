package SubSystems;

import MessageTransport.MessageTransporter;
import MessageTransport.SendAddress;
import common.*;

import java.io.IOException;
import java.net.*;
import java.util.Arrays;

public class DroneSubsystem implements Runnable {
    // Unique identifier for this drone
    private static final String SCHEDULER_HOST = "localhost";
    private static final int SCHEDULER_PORT = 6000;
    private static final int DEFAULT_AGENT_CAPACITY = 100;
    private static final int TRAVEL_TIME = 11300; // milliseconds
    private static final int TIME_TO_OPEN_DOOR = 200; // milliseconds
    private static final double WATER_DROP_RATE = 0.2; // L/s
    private static final int ACCELERATION  = 6; // m/s
    private static final int DECELERATION = 4; // m/s

    private final int droneId;
    private DroneState state = DroneState.IDLE;

    private final DatagramSocket socket;

    private Integer currentZoneId = null;
    private int remainingAgent = DEFAULT_AGENT_CAPACITY;
    private int missionsCompleted = 0;


    // Transport mechanism used to communicate with the Scheduler
    //private final MessageTransporter transport;


    // Indicates whether the drone should attempt to receive a task
    // private boolean waitingForTask = false;

    //for SubSystems.GUI
    private SystemCounts counts = null;

    public DroneSubsystem(int droneId) {
        this(droneId, null);
    }

    /**
     * Constructs a SubSystems.DroneSubsystem.
     *
     * @param droneId   unique ID of the drone
     * @param transport shared MessageTransporter instance
     */
    public DroneSubsystem(int droneId, MessageTransporter transport) {
        this.droneId = droneId;
        //this.transport = transport;
        try {
            this.socket = new DatagramSocket(6100 + droneId);
            socket.setSoTimeout(200);
        } catch (SocketException e) {
            throw new RuntimeException(e);
        }
    }

    public DroneSubsystem(int droneId, MessageTransporter transport, SystemCounts counts) {
        this.droneId = droneId;
        //this.transport = transport;
        try {
            this.socket = new DatagramSocket(6100 + droneId);
            socket.setSoTimeout(200);
        } catch (SocketException e) {
            throw new RuntimeException(e);
        }
        this.counts = counts;
    }

    /**
     -Main execution loop of the Drone Subsystem.
     -The drone continuously polls the Scheduler for tasks,
     -executes received commands, and reports completion.
     */
    @Override
    public void run() {
        System.out.println("[DRONE " + droneId + "] Drone subsystem started");

        try {
            while (!Thread.currentThread().isInterrupted()) {
                sendPoll();
                // Try to receive ONLY if scheduler has sent something
                try {
                    byte[] buf = new byte[2048];
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    socket.receive(packet);

                    Message msg = Message.fromBytes(
                            java.util.Arrays.copyOf(packet.getData(), packet.getLength())
                    );

                    handleMessage(msg);
                } catch (SocketTimeoutException e) {
                    // no message this cycle — safe to ignore
                }


                Thread.sleep(500);
//                waitingForTask = true; // allow receive on next loop
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

    private void sendDone() throws IOException {
        DroneStatus status = new DroneStatus(droneId, state, null, remainingAgent);
        Message doneMsg = Message.droneDone(droneId, status);
        byte[] out = doneMsg.toBytes();

        DatagramPacket packet = new DatagramPacket(
                out, out.length, InetAddress.getByName(SCHEDULER_HOST), SCHEDULER_PORT
        );
        socket.send(packet);
        System.out.println("[DRONE " + droneId + "] Task completed and reported");
    }

    // handle messages sent from scheduler
    private void handleMessage(Message msg) throws InterruptedException, IOException {
        switch (msg.getType()) {
            case DRONE_TASK -> {
                DroneCommand command = (DroneCommand) msg.getPayload();
                System.out.println("[DRONE " + droneId + "] Received task: " + command);

                if (counts != null) {
                    counts.incBusyDrones();
                }

                executeCommand(command);
                sendDone();
                missionsCompleted++;

                if (counts != null) {
                    counts.decBusyDrones();
                }
            }

            default -> {
                // ignore
            }
        }
    }

    private void sendStatusWithPosition(DroneState state, Integer zoneId, Integer remainingAgent, double posX, double posY) throws IOException {
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
    private void travelToBase(long totalMs) throws InterruptedException, IOException {
        int[] src = ZoneMap.get(currentZoneId); // where we're returning from
        long steps = 10;
        long stepMs = totalMs / steps;

        for (int i = 1; i <= steps; i++) {
            Thread.sleep(stepMs);
            double progress = (double) i / steps;
            // interpolate from zone coords back to 0,0
            double currentX = src[0] * (1.0 - progress);
            double currentY = src[1] * (1.0 - progress);
            sendStatusWithPosition(state, null, remainingAgent, currentX, currentY);
        }
    }

    private void executeCommand(DroneCommand command) throws InterruptedException, IOException {
        currentZoneId = command.get_zone_id();
        int distanceMeters = distanceForZone(currentZoneId);
        int amountUsed = agentUsageForSeverity(command.getSeverity());

        transition(DroneEvent.TASK_RECEIVED);

        long outboundMs = computeTravelTimeMs((int) ZoneMap.distanceFromBase(currentZoneId));
        travelToZone(currentZoneId, outboundMs);

        int targetZone = currentZoneId;
        long dropMs = computeDropTimeMs(amountUsed);
        long returnMs = computeTravelTimeMs((int) ZoneMap.distanceFromBase(targetZone));

        System.out.println("[DRONE " + droneId + "] Dispatching to zone " + currentZoneId);

        transition(DroneEvent.ARRIVED);
        sendStatusWithPosition(state, currentZoneId, remainingAgent, ZoneMap.get(currentZoneId)[0], ZoneMap.get(currentZoneId)[1]);
        System.out.println("[DRONE " + droneId + "] Arrived at zone " + currentZoneId);

        System.out.println("[DRONE " + droneId + "] Door opened");
        Thread.sleep(TIME_TO_OPEN_DOOR); // simulate drop time
        System.out.println("[DRONE " + droneId + "] Dropping agent (" + command.getSeverity() + ")") ;
        Thread.sleep(dropMs);

        remainingAgent = Math.max(0, remainingAgent - amountUsed);
        sendStatusWithPosition(state, currentZoneId, remainingAgent, ZoneMap.get(currentZoneId)[0], ZoneMap.get(currentZoneId)[1]);

        transition(DroneEvent.DROP_COMPLETE);
        sendStatus(state, currentZoneId, remainingAgent);

        System.out.println("[DRONE " + droneId + "] Returning to base");
        Thread.sleep(returnMs); // simulate return time

        transition(DroneEvent.RETURN_COMPLETE);
        currentZoneId = null;

        // reset to IDLE after reporting DONE state
        state = DroneState.IDLE;
        System.out.println("[DRONE " + droneId + "] Arrived at base");
    }

    /*
    Method for tracking drone movement

     */
    private void travelToZone(int zoneId, long totalMs) throws InterruptedException, IOException{
        int[] dest = ZoneMap.get(zoneId);
        long steps = 10;
        long stepMs = totalMs/steps;

        for(int i = 1; i <= steps; i++){
            Thread.sleep(stepMs);
            try {
                byte[] buf = new byte[2048];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p); // non-blocking due to setSoTimeout(200)
                Message msg = Message.fromBytes(Arrays.copyOf(p.getData(), p.getLength()));

                if (msg.getType() == MessageType.DRONE_TASK) {
                    DroneCommand redirect = (DroneCommand) msg.getPayload();
                    System.out.println("[DRONE " + droneId + "] Redirected to zone " + redirect.get_zone_id());
                    currentZoneId = redirect.get_zone_id(); // update destination
                    return; // exit travel, executeCommand will re-travel to new zone
                }
            } catch (SocketTimeoutException ignored) {
                // no redirect, continue flying
            }
            double progress = (double) i / steps;
            double currX = dest[0] * progress;
            double currY = dest[1] * progress;
            sendStatusWithPosition(state, zoneId, remainingAgent, currX, currY);
        }
    }

    private long computeDropTimeMs(int litresToDrop){
        double seconds = litresToDrop / WATER_DROP_RATE;
        return (long) Math.ceil(seconds * 1000);
    }

    private long computeTravelTimeMs(double distanceMeters){
        double peakSpeed = Math.sqrt((2 * distanceMeters * ACCELERATION * DECELERATION) / (ACCELERATION + DECELERATION));
        double timeAccelerate = peakSpeed / ACCELERATION;
        double timeDecelerate = peakSpeed / DECELERATION;
        return (long) Math.ceil((timeAccelerate + timeDecelerate) * 1000);
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

    private int distanceForZone(int zoneId){
        return switch (zoneId){
            case 1 -> 500;
            case 2 -> 800;
            case 3 -> 1200;
            case 4 -> 1500;
            default -> 1000;
        };
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