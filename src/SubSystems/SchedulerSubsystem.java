package SubSystems;

import MessageTransport.MessageTransporter;
import common.*;

import javax.swing.*;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.net.*;
import java.io.*;

public class SchedulerSubsystem implements Runnable {

    private MessageTransporter transport;

    private static final int SCHEDULER_PORT = 6000;
    private static final int FIRE_INCIDENT_PORT = 7000;
    private static final String FIRE_INCIDENT_HOST = "localhost";
    private static final int BUFFER_SIZE = 4096;
    private static final int DEFAULT_DRONE_AGENT_CAPACITY = 100;

    private final Map<Integer, DroneInfo> drones = new HashMap<>();
    private final Queue<FireEvent> pendingEvents = new ArrayDeque<>();

    DatagramSocket receiveSocket;
    DatagramSocket sendSocket;

    private SystemCounts counts;
    private SchedulerState schedulerState = SchedulerState.IDLE;

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
        try {
            receiveSocket = new DatagramSocket(6000);
            sendSocket = new DatagramSocket();
        } catch (SocketException e) {
            e.printStackTrace();
        }
        this.counts = counts;
    }

    public SchedulerSubsystem(MessageTransporter transport) {
        try {
            receiveSocket = new DatagramSocket(6000);
            sendSocket = new DatagramSocket();
        } catch (SocketException e) {
            e.printStackTrace();
        }
    }

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

    public void handle(Message msg, InetAddress address, int port) {
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

        if (!drone.busy && drone.lastKnownState != DroneState.OFFLINE) {
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

        drone.markIdle();
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

    private void handleDroneFault(Message msg, InetAddress address, int port) {
        DroneFault fault = (DroneFault) msg.getPayload();
        int droneId = msg.get_source_id();

        sendToGUI(msg);
        System.out.println("[SCHEDULER] Drone fault reported: " + fault);

        DroneInfo drone = getOrCreateDrone(droneId);
        updateDroneEndpoint(drone, address, port);

        FireEvent failedEvent = drone.assignedEvent;
        if (failedEvent != null) {
            FireEvent retryEvent = new FireEvent(
                    failedEvent.getTimestamp(),
                    failedEvent.getZoneId(),
                    failedEvent.getEventType(),
                    failedEvent.getSeverity(),
                    FaultType.NONE,
                    0
            );
            pendingEvents.add(retryEvent);
            System.out.println("[SCHEDULER] Requeued clean event for zone " + failedEvent.getZoneId());
        }

        drone.assignedEvent = null;
        drone.destinationZone = -1;
        drone.currentZoneId = null;
        drone.lastStatusTimeMs = System.currentTimeMillis();

        if (fault.getFaultType() == FaultType.NOZZLE_JAM) {
            drone.markOffline();
            System.out.println("[SCHEDULER] Drone " + droneId + " marked OFFLINE");
        } else {
            drone.available = false;
            drone.busy = true;
            drone.lastKnownState = DroneState.FAULTED;
            System.out.println("[SCHEDULER] Drone " + droneId + " marked temporarily FAULTED");
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

    private DroneInfo findBestDroneForNextEvent(FireEvent event) {
        int requestedZone = event.getZoneId();
        DroneInfo best = null;
        double bestScore = Double.MAX_VALUE;

        for (DroneInfo drone : drones.values()) {
            if (drone.isDispatchable()) {
                double timeToZone = drone.estimateSecondsToZone(requestedZone);
                double score = timeToZone + drone.missionsCompleted * 10.0;
                if (score < bestScore) {
                    bestScore = score;
                    best = drone;
                }
            }

            if (drone.busy
                    && drone.destinationZone != -1
                    && drone.assignedEvent != null
                    && drone.lastKnownState != DroneState.OFFLINE
                    && drone.lastKnownState != DroneState.FAULTED) {

                boolean passesThrough = ZoneMap.isOnPath(drone.destinationZone, requestedZone, 150);
                boolean sameSeverity = event.getSeverity() == drone.assignedEvent.getSeverity();

                if (passesThrough && sameSeverity) {
                    double timeToZone = drone.estimateSecondsToZone(requestedZone);
                    double score = timeToZone;
                    if (score < bestScore) {
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

        System.out.println("[SCHEDULER] Dispatched Drone " + drone.droneId
                + " to zone " + event.getZoneId()
                + " (" + event.getSeverity()
                + ", fault=" + event.getFaultType() + ")");
    }

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
        if (gui != null) {
            SwingUtilities.invokeLater(() -> gui.handleIncomingMessage(msg));
        }
    }

    public static void main(String[] args) {
        SystemCounts counts = null;
        Thread scheduler = new Thread(new SchedulerSubsystem(counts));
        scheduler.start();
    }
}