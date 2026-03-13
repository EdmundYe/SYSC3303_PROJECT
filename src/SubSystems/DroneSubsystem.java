package SubSystems;

import MessageTransport.MessageTransporter;
import MessageTransport.SendAddress;
import common.*;

import java.io.IOException;
import java.net.*;

public class DroneSubsystem implements Runnable {
    // Unique identifier for this drone
    private static final String SCHEDULER_HOST = "localhost";
    private static final int SCHEDULER_PORT = 6000;
    private static final int DEFAULT_AGENT_CAPACITY = 100;

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

    //for GUI
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

    private void executeCommand(DroneCommand command) throws InterruptedException, IOException {
        currentZoneId = command.get_zone_id();

        transition(DroneEvent.TASK_RECEIVED);
        sendStatus(state, currentZoneId, remainingAgent);
        System.out.println("[DRONE " + droneId + "] Dispatching to zone " + currentZoneId);

        Thread.sleep(2000); // simulate travel time

        transition(DroneEvent.ARRIVED);
        sendStatus(state, currentZoneId, remainingAgent);
        System.out.println("[DRONE " + droneId + "] Arrived at zone " + currentZoneId);

        Thread.sleep(2000); // simulate drop time

        int amountUsed = agentUsageForSeverity(command.getSeverity());
        remainingAgent = Math.max(0, remainingAgent - amountUsed);

        sendStatus(state, currentZoneId, remainingAgent);
        System.out.println("[DRONE " + droneId + "] Dropping agent (" + command.getSeverity() + ")");

        transition(DroneEvent.DROP_COMPLETE);
        sendStatus(state, currentZoneId, remainingAgent);

        Thread.sleep(2000); // simulate return time

        System.out.println("[DRONE " + droneId + "] Returning to base");
        transition(DroneEvent.RETURN_COMPLETE);
        currentZoneId = null;

        sendStatus(state, null, remainingAgent);

        // reset to IDLE after reporting DONE state
        state = DroneState.IDLE;
        sendStatus(state, null, remainingAgent);
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