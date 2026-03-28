package SubSystems;

import MessageTransport.MessageTransporter;
import MessageTransport.SendAddress;
import common.*;

import javax.swing.*;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.net.*;
import java.io.*;

public class SchedulerSubsystem implements Runnable{

    private MessageTransporter transport;

    private static final int SCHEDULER_PORT = 6000;
    private static final int FIRE_INCIDENT_PORT = 7000;
    private static final String FIRE_INCIDENT_HOST = "localhost";
    private static final int BUFFER_SIZE = 4096;
    private static final int DEFAULT_DRONE_AGENT_CAPACITY = 100;
    private static final int GUI_PORT = 8000;
    private static final String GUI_HOST = "localhost";


    private final Map<Integer, DroneInfo> drones = new HashMap<>();
    private final Queue<FireEvent> pendingEvents = new ArrayDeque<>();

    DatagramSocket receiveSocket;

    DatagramSocket sendSocket;

    // private final int SINGLE_DRONE_ID = 1;

    // private FireEvent activeEvent = null;

    //for gui
    private SystemCounts counts;

    // Scheduler state machine
    private SchedulerState schedulerState = SchedulerState.IDLE;

//    public SchedulerSubsystem(MessageTransporter transport) {
//        this.transport = transport;
//    }

    // implement SubSystems.GUI running inside scheduler
    private GUI gui;

    public SchedulerSubsystem(SystemCounts counts) {
        try {
            this.receiveSocket = new DatagramSocket(SCHEDULER_PORT);
            this.sendSocket = new DatagramSocket();
        } catch (SocketException e) {
            throw new RuntimeException("Failed to create scheduler sockets", e);
        }
        this.counts = counts;

        try {
            SwingUtilities.invokeAndWait(() -> {
                this.gui = new GUI(counts);
                this.gui.setVisible(true);
            });
        } catch (Exception e) {
            throw new RuntimeException("Failed to start GUI", e);
        }
    }

    public SchedulerSubsystem(MessageTransporter transport, SystemCounts counts) {
        try{
            receiveSocket = new DatagramSocket(6000);
            sendSocket = new DatagramSocket();
        } catch (SocketException e) {
            e.printStackTrace();
        }
        //this.transport = transport;
        this.counts = counts;
    }

    // THIS IS DEPRECATED DO NOT USE, ONLY HERE FOR THE PREVIOUS TESTS
    public SchedulerSubsystem(MessageTransporter transport) {
        try{
            receiveSocket = new DatagramSocket(6000);
            sendSocket = new DatagramSocket();
        } catch (SocketException e) {
            e.printStackTrace();
        }
        //this.transport = transport;
    }
//
//    @Override
//    public void run() {
//        System.out.println("[SCHEDULER] Started");
//        try {
//            while (!Thread.currentThread().isInterrupted()) {
//                Message msg = transport.receive(SendAddress.SCHEDULER);
//                handle(msg);
//            }
//        } catch (InterruptedException e) {
//            Thread.currentThread().interrupt();
//        }
//        System.out.println("[SCHEDULER] Stopped");
//    }

    /**
     * Scheduler listens on UDP port 6000 and reacts to messages from
     * FireIncidentSubsystem and DroneSubsystem.
     */
    public void run() {
        System.out.println("[SCHEDULER] Scheduler started on port " + SCHEDULER_PORT);

        while (true) {
            try {
                byte[] buffer = new byte[BUFFER_SIZE];
                DatagramPacket receivePacket = new DatagramPacket(buffer, buffer.length);
                receiveSocket.receive(receivePacket);

                byte[] data = new byte[receivePacket.getLength()];
                System.arraycopy(
                        receivePacket.getData(),
                        receivePacket.getOffset(),
                        data,
                        0,
                        receivePacket.getLength()
                );

                Message msg = Message.fromBytes(data);
                InetAddress incomingAddress = receivePacket.getAddress();
                int incomingPort = receivePacket.getPort();

                handle(msg, incomingAddress, incomingPort);
            } catch (IOException e) {
                throw new RuntimeException("Scheduler receive loop failed", e);
            }
        }
    }

    void handle(Message msg, InetAddress address, int port) {
        switch (msg.getType()) {
            case FIRE_EVENT -> handleFireEvent(msg, address, port);
            case DRONE_POLL -> handleDronePoll(msg, address, port);
            case DRONE_STATUS -> handleDroneStatus(msg, address, port);
            case DRONE_DONE -> handleDroneDone(msg, address, port);
            case DRONE_FAULT -> handleDroneFault(msg, address, port);
            default -> System.out.println("[SCHEDULER] Ignored message: " + msg);
        }
    }

    private void handleFireEvent(Message msg, InetAddress address, int port) {
        FireEvent event = (FireEvent) msg.getPayload();
        pendingEvents.add(event);

        sendToGUI(msg);
        System.out.println("[SCHEDULER] Received FIRE_EVENT: " + event);
        transition(SchedulerEvent.FIRE_RECEIVED);

        sendAckToFireIncident(address, port);

        if (counts != null) {
            counts.incActiveFires();
        }

        tryDispatchIfPossible();
        refreshSchedulerState();
    }

    private void handleDronePoll(Message msg, InetAddress address, int port) {
        int droneId = (int) msg.getPayload();
        sendToGUI(msg);
        System.out.println("[SCHEDULER] Drone polled: Drone " + droneId);
        transition(SchedulerEvent.DRONE_POLL);

        DroneInfo drone = getOrCreateDrone(droneId);
        updateDroneEndpoint(drone, address, port);

        // only mark it available from poll if it is not already mid-mission
        // Also check if drone is not FAULTED/OFFLINE
        if (!drone.busy && drone.lastKnownState != DroneState.FAULTED &&
                drone.lastKnownState != DroneState.OFFLINE) {
            drone.available = true;
            drone.lastKnownState = DroneState.IDLE;
            drone.lastStatusTimeMs = System.currentTimeMillis();
        }

        tryDispatchIfPossible();
        refreshSchedulerState();
    }

    private void handleDroneStatus(Message msg, InetAddress address, int port) {
        DroneStatus status = (DroneStatus) msg.getPayload();
        int droneId = msg.get_source_id();

        DroneInfo drone = getOrCreateDrone(droneId);
        updateDroneEndpoint(drone, address, port);
        drone.applyStatus(status);

        sendToGUI(msg);
        System.out.println("[SCHEDULER] Drone status update: " + status);
        transition(SchedulerEvent.DRONE_STATUS_RECEIVED);

        refreshSchedulerState();
    }

    private void handleDroneDone(Message msg, InetAddress address, int port) {
        DroneStatus status = (DroneStatus) msg.getPayload();
        int droneId = msg.get_source_id();

        sendToGUI(msg);
        System.out.println("[SCHEDULER] Drone completed task: " + status);
        transition(SchedulerEvent.DRONE_DONE_RECEIVED);

        DroneInfo drone = getOrCreateDrone(droneId);
        updateDroneEndpoint(drone, address, port);

        FireEvent completedEvent = drone.assignedEvent;

        if (status.getState() == DroneState.FAULTED) {
            System.out.println("[SCHEDULER] Drone " + droneId + " completed task but remains FAULTED - NOT marking available");
            drone.busy = false;
            drone.lastKnownState = DroneState.FAULTED;
            drone.available = false;
            drone.assignedEvent = null;

        } else if (status.getState() == DroneState.OFFLINE) {
            System.out.println("[SCHEDULER] Drone " + droneId + " completed task but is OFFLINE - NOT marking available");
            drone.busy = false;
            drone.lastKnownState = DroneState.OFFLINE;
            drone.available = false;
            drone.assignedEvent = null;

        } else {
            // Normal case: mark idle and available
            drone.markIdle();
            drone.available = true;
        }

        drone.remainingAgent = status.get_remaining_agent();
        drone.missionsCompleted++;

        if (completedEvent != null) {
            notifyFireOut(completedEvent);
        }

        if (counts != null) {
            counts.decActiveFires();
        }

        tryDispatchIfPossible();
        refreshSchedulerState();
    }

    private DroneInfo getOrCreateDrone(int droneId) {
        DroneInfo drone = drones.get(droneId);
        if (drone == null) {
            drone = new DroneInfo(droneId);
            drone.remainingAgent = DEFAULT_DRONE_AGENT_CAPACITY;
            drones.put(droneId, drone);
            System.out.println("[SCHEDULER] Registered Drone " + droneId);
        }
        return drone;
    }

    private void updateDroneEndpoint(DroneInfo drone, InetAddress address, int port) {
        drone.setListenAddress(address);
        drone.setListenPort(port);
    }

    //keep dispatching while there is pending work and at least one dispatchable drone
    private void tryDispatchIfPossible() {
        refreshSchedulerState();

        while (!pendingEvents.isEmpty()) {
            FireEvent nextEvent = pendingEvents.peek();
            DroneInfo bestDrone = findBestDroneForNextEvent(nextEvent);

            if (bestDrone == null) {
                refreshSchedulerState();
                return;
            }

            FireEvent event = pendingEvents.poll();
            dispatch(bestDrone, event);

            transition(SchedulerEvent.DISPATCH_SENT);
            refreshSchedulerState();
        }

        refreshSchedulerState();
    }

    /**
     1. Only dispatch drones that are reachable and available
     2. Prefer the drone with fewer completed missions
     3. Tie-break by lower drone ID
     */
    private DroneInfo findBestDroneForNextEvent(FireEvent event) {
        int requestedZone = event.getZoneId();
        DroneInfo best = null;
        double bestScore = Double.MAX_VALUE;

        for (DroneInfo drone : drones.values()) {
            // Skip FAULTED/OFFLINE drones completely
            if (drone.lastKnownState == DroneState.FAULTED ||
                    drone.lastKnownState == DroneState.OFFLINE) {
                continue;
            }

            if(drone.isDispatchable()){
                double timeToZone = drone.estimateSecondsToZone(requestedZone);
                double score = timeToZone + drone.missionsCompleted * 10.0;
                if (score < bestScore) {
                    bestScore = score;
                    best = drone;
                }
            }

            if (drone.busy && drone.destinationZone != -1 && drone.assignedEvent != null){
                boolean passesThrough = ZoneMap.isOnPath(drone.destinationZone, requestedZone, 150);
                boolean sameSeverity = event.getSeverity() == drone.assignedEvent.getSeverity();
                if (passesThrough && sameSeverity){
                    double timeToZone = drone.estimateSecondsToZone(requestedZone);
                    double score = timeToZone;
                    if (score < bestScore){
                        bestScore = score;
                        best = drone;
                    }
                }
            }
        }
        return best;
    }

    private void dispatch(DroneInfo drone, FireEvent event) {
        drone.markBusy(event);
        if (drone.remainingAgent == null) {
            drone.remainingAgent = DEFAULT_DRONE_AGENT_CAPACITY;
        }

        DroneCommand command = new DroneCommand(
                "REQ-" + event.getTimestamp().toEpochMilli(),
                DroneCommandOptions.DISPATCH,
                event.getZoneId(),
                event.getSeverity(),
                event.getFaultType(),
                event.getFaultDelaySeconds()
        );

        String faultInfo = (event.getFaultType() != FaultType.NONE) ?
                " - Fault: " + event.getFaultType() +
                        " (delay: " + event.getFaultDelaySeconds() + "s)" : "";

        System.out.println("[SCHEDULER] Dispatched Drone " + drone.droneId
                + " to zone " + event.getZoneId()
                + " (" + event.getSeverity() + ")" + faultInfo);

        Message taskMessage = Message.droneTask(drone.droneId, command);
        byte[] payload = taskMessage.toBytes();

        DatagramPacket packet = new DatagramPacket(
                payload,
                payload.length,
                drone.getListenAddress(),
                drone.getListenPort()
        );

        try {
            sendSocket.send(packet);
            sendToGUI(taskMessage);

        } catch (IOException e) {
            throw new RuntimeException("Failed to dispatch Drone " + drone.droneId, e);
        }
    }

    // Fire subsystem currently expects an ACK after each FIRE_EVENT send
    private void sendAckToFireIncident(InetAddress address, int port) {
        Message ack = new Message(MessageType.FIRE_EVENT, 0, null);
        byte[] ackBytes = ack.toBytes();

        DatagramPacket ackPacket = new DatagramPacket(
                ackBytes,
                ackBytes.length,
                address,
                port
        );

        try {
            sendSocket.send(ackPacket);
            System.out.println("[SCHEDULER] Sent FIRE_EVENT ACK");
        } catch (IOException e) {
            throw new RuntimeException("Failed to send fire ACK", e);
        }
    }


    // Notify the Fire Incident subsystem that a zone has been serviced
    private void notifyFireOut(FireEvent event) {
        Message out = Message.fireOut(event);
        byte[] bytes = out.toBytes();

        try {
            DatagramPacket packet = new DatagramPacket(
                    bytes,
                    bytes.length,
                    InetAddress.getByName(FIRE_INCIDENT_HOST),
                    FIRE_INCIDENT_PORT
            );
            sendSocket.send(packet);
            sendToGUI(out);
            System.out.println("[SCHEDULER] Sent FIRE_OUT for zone " + event.getZoneId());
        } catch (IOException e) {
            throw new RuntimeException("Failed to send FIRE_OUT", e);
        }
    }

    private boolean hasBusyDrones() {
        for (DroneInfo drone : drones.values()) {
            if (drone.busy) {
                return true;
            }
        }
        return false;
    }

    private boolean hasDispatchableDrone() {
        for (DroneInfo drone : drones.values()) {
            if (drone.isDispatchable()) {
                return true;
            }
        }
        return false;
    }

    /**
     * IDLE:
        no pending incidents and no drones currently working
     * SENDING_DRONES:
        there is pending work and at least one dispatchable drone
     * WAITING_FOR_DRONES:
        unresolved work still exists, but no drone can be dispatched right now,
        or drones are still in the field
     */
    private void refreshSchedulerState() {
        SchedulerState oldState = schedulerState;

        boolean hasPending = !pendingEvents.isEmpty();
        boolean hasBusy = hasBusyDrones();
        boolean hasAvailable = hasDispatchableDrone();

        if (!hasPending && !hasBusy) {
            schedulerState = SchedulerState.IDLE;
        } else if (hasPending && hasAvailable) {
            schedulerState = SchedulerState.SENDING_DRONES;
        } else {
            schedulerState = SchedulerState.WAITING_FOR_DRONES;
        }

        if (oldState != schedulerState) {
            System.out.println("[SCHEDULER] State: " + oldState + " -> " + schedulerState);
        }
    }

    /**
     * Keeps your enum-based transition style for logging/traceability.
     * The actual effective Iteration 3 state is refreshed from real system conditions.
     */
    private void transition(SchedulerEvent event) {
        SchedulerState before = schedulerState;
        schedulerState = schedulerState.next(event);

        if (before != schedulerState) {
            System.out.println("[SCHEDULER] Transition: " + before + " -> " + schedulerState + " on " + event);
        } else {
            System.out.println("[SCHEDULER] Event: " + event);
        }
    }

    private void sendToGUI(Message msg) {
        if(gui != null){
            SwingUtilities.invokeLater(()-> gui.handleIncomingMessage(msg));
        }
    }

    /**
     * Handles DRONE_FAULT messages from drones
     * Iteration 4: Processes fault notifications and prevents re-dispatch
     *
     * @param msg Message containing fault type
     * @param address Address of the faulty drone
     * @param port Port of the faulty drone
     */
    private void handleDroneFault(Message msg, InetAddress address, int port) {
        int droneId = msg.get_source_id();
        FaultType faultType = (FaultType) msg.getPayload();
        String timestamp = getCurrentTimeFormatted();

        System.out.println("[SCHEDULER] [" + timestamp + "] FAULT received from Drone " +
                droneId + ": " + faultType);

        DroneInfo drone = drones.get(droneId);
        if (drone == null) {
            System.out.println("[SCHEDULER] WARNING: Fault from unknown drone " + droneId);
            return;
        }

        // Update drone state based on fault type
        if (faultType == FaultType.NOZZLE_JAM) {
            System.out.println("[SCHEDULER] [" + timestamp + "] Drone " + droneId +
                    " is PERMANENTLY OFFLINE (NOZZLE_JAM)");
            drone.available = false;
            drone.busy = false;
            drone.lastKnownState = DroneState.OFFLINE;

        } else if (faultType == FaultType.DRONE_STUCK) {
            System.out.println("[SCHEDULER] [" + timestamp + "] Drone " + droneId +
                    " is FAULTED (DRONE_STUCK - temporary)");
            drone.available = false;  // KEY: Mark as NOT available
            drone.busy = false;
            drone.lastKnownState = DroneState.FAULTED;  // KEY: Set state
        }

        // Reassign the task if any
        if (drone.assignedEvent != null) {
            System.out.println("[SCHEDULER] [" + timestamp + "] Reassigning task from Drone " + droneId);
            pendingEvents.add(drone.assignedEvent);
            drone.assignedEvent = null;
        }

        tryDispatchIfPossible();
        refreshSchedulerState();
    }

    /**
     * Helper method to get current time formatted for logging
     * Format: HH:mm:ss.SSS
     *
     * @return Current timestamp as formatted string
     */
    private String getCurrentTimeFormatted() {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        java.time.format.DateTimeFormatter formatter =
                java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
        return now.format(formatter);
    }

    public static void main(String[] args) {
        SystemCounts counts = null;

        Thread scheduler = new Thread(new SchedulerSubsystem(counts));
        scheduler.start();
    }
}
