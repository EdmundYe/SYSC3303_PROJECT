package SubSystems;

import MessageTransport.MessageTransporter;
import common.*;

import javax.swing.*;
import java.net.*;
import java.io.*;
import java.util.*;

public class SchedulerSubsystem implements Runnable {

    private MessageTransporter transport;

    private static final int SCHEDULER_PORT = 6000;
    private static final int FIRE_INCIDENT_PORT = 7000;
    private static final String FIRE_INCIDENT_HOST = "localhost";
    private static final int BUFFER_SIZE = 4096;
    private static final int DEFAULT_DRONE_AGENT_CAPACITY = 100;
    private static final Map<String, Integer> SEVERITY_DRONE_COUNT = Map.of(
            "low",      1,
            "moderate", 2,
            "high",     3
    );

    private final Map<Integer, Integer> zoneActiveDroneCount = new HashMap<>();
    private final Map<Integer, DroneInfo> drones = new HashMap<>();
    private final Queue<FireEvent> pendingEvents = new ArrayDeque<>();
    private final Set<Integer> activeZones = new HashSet<>();
    private final SimulationMetrics metrics = new SimulationMetrics();

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
        if (counts != null) {
            metrics.registerDroneRange(counts.getTotalDrones());
        }

        try {
            SwingUtilities.invokeAndWait(() -> {
                this.gui = new GUI(counts);
                this.gui.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
                this.gui.addWindowListener(new java.awt.event.WindowAdapter() {
                    @Override
                    public void windowClosing(java.awt.event.WindowEvent e) {
                        try {
                            System.out.println("[SCHEDULER] GUI closing, writing metrics report...");
                            metrics.printFinalReport();
                        } catch (Exception ex) {
                            System.err.println("[SCHEDULER] Failed to write metrics report on GUI close: " + ex.getMessage());
                        } finally {
                            gui.dispose();
                            System.exit(0);
                        }
                    }
                });
                this.gui.setVisible(true);
            });
        } catch (Exception e) {
            throw new RuntimeException("Failed to start GUI", e);
        }

    }

    public SchedulerSubsystem(MessageTransporter transport, SystemCounts counts) {
        try {
            receiveSocket = new DatagramSocket(SCHEDULER_PORT);
            sendSocket = new DatagramSocket();
        } catch (SocketException e) {
            throw new RuntimeException(e);
        }
        this.counts = counts;
        this.transport = transport;
    }

    public SchedulerSubsystem(MessageTransporter transport) {
        try {
            receiveSocket = new DatagramSocket(SCHEDULER_PORT);
            sendSocket = new DatagramSocket();
        } catch (SocketException e) {
            throw new RuntimeException(e);
        }
        this.transport = transport;
    }

    @Override
    public void run() {
        if (common.DebugOutputFilter.isSchedulerOutputActive())
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
            default -> {
                if (common.DebugOutputFilter.isSchedulerOutputActive())
                    System.out.println("[SCHEDULER] Ignored message: " + msg);
            }
        }
    }

    private void handleFireEvent(Message msg, InetAddress address, int port) {
        FireEvent event = (FireEvent) msg.getPayload();

        if (isDuplicateIncident(event)) {
            if (common.DebugOutputFilter.isSchedulerOutputActive())
                System.out.println("[SCHEDULER] Ignoring exact duplicate FIRE_EVENT: " + event);
            sendAckToFireIncident(address, port);
            return;
        }

        pendingEvents.add(event);
        metrics.recordFireDetected(event);

        sendToGUI(msg);
        if (common.DebugOutputFilter.isSchedulerOutputActive())
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
        metrics.registerDrone(droneId);
        if (common.DebugOutputFilter.isSchedulerOutputActive())
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
        metrics.recordDroneStatus(status);

        sendToGUI(msg);
        if (common.DebugOutputFilter.isSchedulerOutputActive())
            System.out.println("[SCHEDULER] Drone status update: " + status);
        transition(SchedulerEvent.DRONE_STATUS_RECEIVED);

        refreshSchedulerState();
    }

    private void handleDroneDone(Message msg, InetAddress address, int port) {
        DroneStatus status = (DroneStatus) msg.getPayload();
        int droneId = msg.get_source_id();

        sendToGUI(msg);
        if (common.DebugOutputFilter.isSchedulerOutputActive())
            System.out.println("[SCHEDULER] Drone completed task: " + status);
        transition(SchedulerEvent.DRONE_DONE_RECEIVED);

        DroneInfo drone = getOrCreateDrone(droneId);
        updateDroneEndpoint(drone, address, port);

        FireEvent completedEvent = drone.assignedEvent;
        drone.markIdle();
        drone.remainingAgent = status.get_remaining_agent();
        drone.batteryLevel = status.get_battery_level();
        drone.missionsCompleted++;
        metrics.recordDroneDone(droneId);

        if (completedEvent != null) {
            int zoneId = completedEvent.getZoneId();
            int remaining = zoneActiveDroneCount.getOrDefault(zoneId, 1) - 1;
            if (remaining <= 0) {
                zoneActiveDroneCount.remove(zoneId);
                activeZones.remove(zoneId);
                notifyFireOut(completedEvent);
                metrics.recordFireOut(completedEvent);
                if (counts != null) counts.decActiveFires();
            } else {
                zoneActiveDroneCount.put(zoneId, remaining);
            }
        }

        reconcileZoneState();
        tryDispatchIfPossible();
        refreshSchedulerState();
    }

    private void handleDroneFault(Message msg, InetAddress address, int port) {
        DroneFault fault = (DroneFault) msg.getPayload();
        int droneId = msg.get_source_id();

        sendToGUI(msg);
        if (common.DebugOutputFilter.isSchedulerOutputActive())
            System.out.println("[SCHEDULER] Drone fault reported: " + fault);
        metrics.recordDroneFault(droneId);

        DroneInfo drone = getOrCreateDrone(droneId);
        updateDroneEndpoint(drone, address, port);

        FireEvent failedEvent = drone.assignedEvent;

        if (failedEvent != null) {
            int zoneId = failedEvent.getZoneId();
            int remaining = zoneActiveDroneCount.getOrDefault(zoneId, 1) - 1;

            if (remaining <= 0) {
                zoneActiveDroneCount.remove(zoneId);
                activeZones.remove(zoneId);
            } else {
                zoneActiveDroneCount.put(zoneId, remaining);
            }
        }

        if (failedEvent != null && !isZonePending(failedEvent.getZoneId())) {
            FireEvent retryEvent = new FireEvent(
                    failedEvent.getTimestamp(),
                    failedEvent.getZoneId(),
                    failedEvent.getEventType(),
                    failedEvent.getSeverity(),
                    FaultType.NONE,
                    0
            );
            pendingEvents.add(retryEvent);

            sendToGUI(Message.fireEvent(retryEvent));

            if (common.DebugOutputFilter.isSchedulerOutputActive())
                System.out.println("[SCHEDULER] Requeued clean event for zone " + failedEvent.getZoneId());
        }

        drone.assignedEvent = null;
        drone.destinationZone = -1;
        drone.currentZoneId = null;
        drone.lastStatusTimeMs = System.currentTimeMillis();

        if (fault.getFaultType() == FaultType.NOZZLE_JAM) {
            drone.markOffline();
            if (common.DebugOutputFilter.isSchedulerOutputActive())
                System.out.println("[SCHEDULER] Drone " + droneId + " marked OFFLINE");
        } else {
            drone.available = false;
            drone.busy = true;
            drone.lastKnownState = DroneState.FAULTED;
            if (common.DebugOutputFilter.isSchedulerOutputActive())
                System.out.println("[SCHEDULER] Drone " + droneId + " marked temporarily FAULTED");
        }

        reconcileZoneState();
        tryDispatchIfPossible();
        refreshSchedulerState();
    }

    private DroneInfo getOrCreateDrone(int droneId) {
        DroneInfo drone = drones.get(droneId);
        if (drone == null) {
            drone = new DroneInfo(droneId);
            drone.remainingAgent = DEFAULT_DRONE_AGENT_CAPACITY;
            drone.batteryLevel = 100;
            drones.put(droneId, drone);
            metrics.registerDrone(droneId);
            if (common.DebugOutputFilter.isSchedulerOutputActive())
                System.out.println("[SCHEDULER] Registered Drone " + droneId);
        }
        return drone;
    }

    private void updateDroneEndpoint(DroneInfo drone, InetAddress address, int port) {
        drone.setListenAddress(address);
        drone.setListenPort(port);
    }

    private void tryDispatchIfPossible() {
        reconcileZoneState();
        refreshSchedulerState();

        while (!pendingEvents.isEmpty()) {
            boolean dispatched = false;

            for (FireEvent event : new ArrayList<>(pendingEvents)) {
                if (isZoneActive(event.getZoneId())) {
                    continue;
                }

                String severity = event.getSeverity().toString().toLowerCase();
                int dronesNeeded = SEVERITY_DRONE_COUNT.getOrDefault(severity, 1);

                DroneInfo redirectCandidate = findPassThroughDrone(event);
                if (redirectCandidate != null) {
                    pendingEvents.remove(event);
                    redirect(redirectCandidate, event);
                    transition(SchedulerEvent.DISPATCH_SENT);
                    reconcileZoneState();
                    refreshSchedulerState();
                    dispatched = true;
                    break;
                }

                List<DroneInfo> dronesList = new ArrayList<>();
                Set<Integer> assignedIds = new HashSet<>();

                for (int i = 0; i < dronesNeeded; i++) {
                    DroneInfo candidate = findBestIdleDroneForEvent(event, assignedIds, dronesNeeded);
                    if (candidate == null) break;
                    dronesList.add(candidate);
                    assignedIds.add(candidate.droneId);
                }

                if (dronesList.isEmpty()) {
                    continue;
                }

                pendingEvents.remove(event);
                if (dronesList.size() == 1) {
                    dispatch(dronesList.get(0), event);
                } else {
                    dispatchMultiple(dronesList, event);
                }

                transition(SchedulerEvent.DISPATCH_SENT);
                reconcileZoneState();
                refreshSchedulerState();
                dispatched = true;
                break;
            }

            if (!dispatched) {
                return;
            }
        }
    }

    private DroneInfo findPassThroughDrone(FireEvent event) {
        int requestedZone = event.getZoneId();
        DroneInfo best = null;
        double bestScore = Double.MAX_VALUE;

        for (DroneInfo drone : drones.values()) {
            if (!drone.busy
                    || drone.destinationZone == -1
                    || drone.assignedEvent == null
                    || drone.lastKnownState == DroneState.OFFLINE
                    || drone.lastKnownState == DroneState.FAULTED) continue;

            if (event.getSeverity() != drone.assignedEvent.getSeverity()) continue;

            boolean passesThrough = ZoneMap.isOnPath(drone.destinationZone, requestedZone, 150);
            if (!passesThrough) continue;

            double score = drone.estimateSecondsToZone(requestedZone) + drone.missionsCompleted * 10.0;
            if (score < bestScore) {
                bestScore = score;
                best = drone;
            }
        }
        return best;
    }

    private DroneInfo findBestIdleDroneForEvent(FireEvent event, Set<Integer> excludedIds, int dronesNeeded) {
        int requestedZone = event.getZoneId();
        int requiredAgent = (int) Math.ceil(
                (double) event.getSeverity().requiredAgentLitres() / dronesNeeded);

        DroneInfo best = null;
        double bestScore = Double.MAX_VALUE;

        for (DroneInfo drone : drones.values()) {
            if (excludedIds.contains(drone.droneId)) continue;
            if (!drone.isDispatchable()) continue;
            if (drone.remainingAgent != null && drone.remainingAgent < requiredAgent) continue;

            double score = drone.estimateSecondsToZone(requestedZone) + drone.missionsCompleted * 10.0;
            if (score < bestScore) {
                bestScore = score;
                best = drone;
            }
        }
        return best;
    }

    private void redirect(DroneInfo drone, FireEvent newEvent) {
        FireEvent oldEvent = drone.assignedEvent;

        int oldZone = oldEvent.getZoneId();
        int oldRemaining = zoneActiveDroneCount.getOrDefault(oldZone, 1) - 1;
        if (oldRemaining <= 0) {
            zoneActiveDroneCount.remove(oldZone);
            activeZones.remove(oldZone);
            if (!isZonePending(oldZone) && !isZoneActive(oldZone)) {
                pendingEvents.add(oldEvent);
                System.out.println("[SCHEDULER] Requeued old event for zone " + oldZone
                        + " after redirect");
            }
        } else {
            zoneActiveDroneCount.put(oldZone, oldRemaining);
        }

        drone.markBusy(newEvent);
        activeZones.add(newEvent.getZoneId());
        zoneActiveDroneCount.merge(newEvent.getZoneId(), 1, Integer::sum);

        int agentAmount = newEvent.getSeverity().requiredAgentLitres();
        DroneCommand command = new DroneCommand(
                "REQ-" + newEvent.getTimestamp().toEpochMilli(),
                DroneCommandOptions.DISPATCH,
                newEvent.getZoneId(),
                newEvent.getSeverity(),
                agentAmount,
                newEvent.getFaultType(),
                newEvent.getFaultDelaySeconds()
        );

        Message taskMessage = Message.droneTask(drone.droneId, command);
        byte[] payload = taskMessage.toBytes();

        DatagramPacket packet = new DatagramPacket(
                payload, payload.length,
                drone.getListenAddress(), drone.getListenPort()
        );

        try {
            sendSocket.send(packet);
            sendToGUI(taskMessage);
        } catch (IOException e) {
            throw new RuntimeException("Failed to redirect Drone " + drone.droneId, e);
        }

        System.out.println("[SCHEDULER] Redirected Drone " + drone.droneId
                + " from zone " + oldZone + " -> zone " + newEvent.getZoneId());
    }

    private void dispatch(DroneInfo drone, FireEvent event) {
        drone.markBusy(event);
        if (drone.remainingAgent == null) {
            drone.remainingAgent = DEFAULT_DRONE_AGENT_CAPACITY;
        }

        reconcileZoneState();

        int agentAmount = event.getSeverity().requiredAgentLitres();

        DroneCommand command = new DroneCommand(
                "REQ-" + event.getTimestamp().toEpochMilli(),
                DroneCommandOptions.DISPATCH,
                event.getZoneId(),
                event.getSeverity(),
                agentAmount,
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
            sendToGUI(Message.fireEvent(event));
        } catch (IOException e) {
            throw new RuntimeException("Failed to dispatch Drone " + drone.droneId, e);
        }

        if (common.DebugOutputFilter.isSchedulerOutputActive())
            System.out.println("[SCHEDULER] Dispatched Drone " + drone.droneId
                    + " to zone " + event.getZoneId()
                    + " (" + event.getSeverity()
                    + ", fault=" + event.getFaultType() + ")");
    }

    private void dispatchMultiple(List<DroneInfo> listOfDrones, FireEvent event) {
        int totalAgent = event.getSeverity().requiredAgentLitres();
        int agentPerDrone = (int) Math.ceil((double) totalAgent / listOfDrones.size());

        for (DroneInfo drone : listOfDrones) {
            drone.markBusy(event);
            if (drone.remainingAgent == null) {
                drone.remainingAgent = DEFAULT_DRONE_AGENT_CAPACITY;
            }
        }

        reconcileZoneState();

        for (DroneInfo drone : listOfDrones) {
            DroneCommand command = new DroneCommand(
                    "REQ-" + event.getTimestamp().toEpochMilli(),
                    DroneCommandOptions.DISPATCH,
                    event.getZoneId(),
                    event.getSeverity(),
                    agentPerDrone,
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
                sendToGUI(Message.fireEvent(event));
            } catch (IOException e) {
                throw new RuntimeException("Failed to dispatch Drone " + drone.droneId, e);
            }

            if (common.DebugOutputFilter.isSchedulerOutputActive())
                System.out.println("[SCHEDULER] Dispatched Drone " + drone.droneId
                        + " to zone " + event.getZoneId()
                        + " (" + event.getSeverity()
                        + ", fault=" + event.getFaultType() + ")");
        }
    }

    private void reconcileZoneState() {
        activeZones.clear();
        zoneActiveDroneCount.clear();

        for (DroneInfo drone : drones.values()) {
            if (drone.assignedEvent != null) {
                int zoneId = drone.assignedEvent.getZoneId();
                activeZones.add(zoneId);
                zoneActiveDroneCount.merge(zoneId, 1, Integer::sum);
            }
        }
    }

    private boolean isZoneActive(int zoneId) {
        return activeZones.contains(zoneId);
    }

    private boolean isZonePending(int zoneId) {
        for (FireEvent event : pendingEvents) {
            if (event.getZoneId() == zoneId) {
                return true;
            }
        }
        return false;
    }

    private boolean sameIncident(FireEvent a, FireEvent b) {
        return a.getZoneId() == b.getZoneId()
                && a.getTimestamp().equals(b.getTimestamp())
                && a.getEventType() == b.getEventType()
                && a.getSeverity() == b.getSeverity()
                && a.getFaultType() == b.getFaultType()
                && a.getFaultDelaySeconds() == b.getFaultDelaySeconds();
    }

    private boolean isDuplicateIncident(FireEvent incoming) {
        for (FireEvent event : pendingEvents) {
            if (sameIncident(event, incoming)) {
                return true;
            }
        }

        for (DroneInfo drone : drones.values()) {
            if (drone.assignedEvent != null && sameIncident(drone.assignedEvent, incoming)) {
                return true;
            }
        }

        return false;
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
            if (common.DebugOutputFilter.isSchedulerOutputActive())
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
            if (common.DebugOutputFilter.isSchedulerOutputActive())
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

    private boolean hasPendingEvents() {
        return !pendingEvents.isEmpty();
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
            if (common.DebugOutputFilter.isSchedulerOutputActive())
                System.out.println("[SCHEDULER] State: " + oldState + " -> " + schedulerState);
        }
    }

    private void transition(SchedulerEvent event) {
        SchedulerState before = schedulerState;
        schedulerState = schedulerState.next(event);

        if (before != schedulerState) {
            if (common.DebugOutputFilter.isSchedulerOutputActive())
                System.out.println("[SCHEDULER] Transition: " + before + " -> " + schedulerState + " on " + event);
        } else {
            if (common.DebugOutputFilter.isSchedulerOutputActive())
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