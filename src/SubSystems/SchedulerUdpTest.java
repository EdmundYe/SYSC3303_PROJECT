package SubSystems;

import common.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.Map;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;

class SchedulerUdpTest {

    private SchedulerSubsystem scheduler;
    private SystemCounts counts;

    private DatagramSocket fireSocket;
    private DatagramSocket drone1Socket;
    private DatagramSocket drone2Socket;
    private DatagramSocket drone3Socket;

    private FireIncidentSubsystem fireParser;

    /**
     * Initializes a fresh scheduler instance, creates mock UDP sockets for
     * drones and the fire subsystem, and prepares a FireIncidentSubsystem
     * for parsing CSV fire lines.
     */
    @BeforeEach
    void setUp() throws Exception {
        counts = new SystemCounts();
        scheduler = new SchedulerSubsystem(counts);

        fireSocket = new DatagramSocket(7000);
        fireSocket.setSoTimeout(1000);

        drone1Socket = new DatagramSocket(0);
        drone2Socket = new DatagramSocket(0);
        drone3Socket = new DatagramSocket(0);

        drone1Socket.setSoTimeout(1000);
        drone2Socket.setSoTimeout(1000);
        drone3Socket.setSoTimeout(1000);

        fireParser = new FireIncidentSubsystem("input.csv");
    }

    /**
     * Closes all sockets created during the test to avoid port leaks.
     */
    @AfterEach
    void tearDown() {
        closeQuietly(fireSocket);
        closeQuietly(drone1Socket);
        closeQuietly(drone2Socket);
        closeQuietly(drone3Socket);

        if (scheduler != null) {
            closeQuietly(scheduler.receiveSocket);
            closeQuietly(scheduler.sendSocket);
        }
    }

    /**
     * Verifies that the scheduler dispatches the first three fire events to
     * three registered drones and leaves the fourth event pending. Also checks
     * that DRONE_TASK messages are sent and that scheduler state transitions
     * to WAITING_FOR_DRONES.
     */
    @Test
    void dispatchesFirstThreeEventsToThreeRegisteredDronesAndLeavesFourthPending() throws Exception {
        registerDrone(1, drone1Socket);
        registerDrone(2, drone2Socket);
        registerDrone(3, drone3Socket);

        sendFireLine("14:03:15,3,FIRE_DETECTED,HIGH");
        sendFireLine("14:03:15,2,FIRE_DETECTED,LOW");
        sendFireLine("14:03:15,1,FIRE_DETECTED,MODERATE");
        sendFireLine("14:03:15,4,FIRE_DETECTED,HIGH");

        // consume 4 fire ACKs
        assertFireAck(receiveMessage(fireSocket));
        assertFireAck(receiveMessage(fireSocket));
        assertFireAck(receiveMessage(fireSocket));
        assertFireAck(receiveMessage(fireSocket));

        Message d1 = receiveMessage(drone1Socket);
        Message d2 = receiveMessage(drone2Socket);
        Message d3 = receiveMessage(drone3Socket);

        assertEquals(MessageType.DRONE_TASK, d1.getType());
        assertEquals(MessageType.DRONE_TASK, d2.getType());
        assertEquals(MessageType.DRONE_TASK, d3.getType());

        DroneCommand c1 = (DroneCommand) d1.getPayload();
        DroneCommand c2 = (DroneCommand) d2.getPayload();
        DroneCommand c3 = (DroneCommand) d3.getPayload();

        assertEquals(FaultType.NONE, c1.getFaultType());
        assertEquals(0, c1.getFaultDelaySeconds());

        assertEquals(FaultType.NONE, c2.getFaultType());
        assertEquals(0, c2.getFaultDelaySeconds());

        assertEquals(FaultType.NONE, c3.getFaultType());
        assertEquals(0, c3.getFaultDelaySeconds());

        // Scheduler tie-breaker is missionsCompleted, then lower droneId.
        assertEquals(3, c1.get_zone_id());
        assertEquals(Severity.HIGH, c1.getSeverity());
        assertEquals(3, c3.get_zone_id());
        assertEquals(Severity.HIGH, c3.getSeverity());
        assertEquals(3, c2.get_zone_id());
        assertEquals(Severity.HIGH, c2.getSeverity());



        assertEquals(3, getPendingQueueSize());

        Map<Integer, DroneInfo> drones = getDroneMap();
        assertTrue(drones.get(1).busy);
        assertTrue(drones.get(2).busy);
        assertTrue(drones.get(3).busy);

        assertEquals(SchedulerState.WAITING_FOR_DRONES, getSchedulerState());
    }

    /**
     * Ensures that when a drone completes its task (DRONE_DONE), the scheduler:
     * */
    @Test
    void dispatchesPendingEventWhenDroneReportsDone_newScheduler() throws Exception {
        // Register 3 drones
        registerDrone(1, drone1Socket);
        registerDrone(2, drone2Socket);
        registerDrone(3, drone3Socket);

        // Send 3 fires that will be dispatched immediately
        sendFireLine("14:03:15,3,FIRE_DETECTED,LOW,NONE,0");
        sendFireLine("14:03:15,2,FIRE_DETECTED,LOW,NONE,0");
        sendFireLine("14:03:15,1,FIRE_DETECTED,LOW,NONE,0");

        // Send a 4th fire that will go into the pending queue
        sendFireLine("14:03:15,4,FIRE_DETECTED,LOW,NONE,0");

        // Receive 4 ACKs from fire subsystem
        for (int i = 0; i < 4; i++) {
            assertFireAck(receiveMessage(fireSocket));
        }

        // Receive 3 DRONE_TASK messages (one for each drone)
        receiveMessage(drone1Socket);
        receiveMessage(drone2Socket);
        receiveMessage(drone3Socket);

        // One event should be pending
        assertEquals(1, getPendingQueueSize());

        // Drone 1 finishes its task
        DroneStatus doneStatus = new DroneStatus(1, DroneState.IDLE, null, 80);
        Message done = Message.droneDone(1, doneStatus);

        scheduler.handle(done, InetAddress.getLoopbackAddress(), drone1Socket.getLocalPort());

        // FIRE_OUT should be sent for the completed event
        Message fireOut = receiveMessage(fireSocket);
        assertEquals(MessageType.FIRE_OUT, fireOut.getType());
        assertNotNull(fireOut.getPayload());

        // Drone 1 should receive the pending event as a new task
        Message nextTask = receiveMessage(drone1Socket);
        assertEquals(MessageType.DRONE_TASK, nextTask.getType());

        DroneCommand cmd = (DroneCommand) nextTask.getPayload();
        assertEquals(4, cmd.get_zone_id());
        assertEquals(Severity.LOW, cmd.getSeverity());
        assertEquals(FaultType.NONE, cmd.getFaultType());
        assertEquals(0, cmd.getFaultDelaySeconds());

        // Pending queue should now be empty
        assertEquals(0, getPendingQueueSize());

        // Drone 1 should now be busy again and have 1 completed mission
        Map<Integer, DroneInfo> drones = getDroneMap();
        assertEquals(1, drones.get(1).missionsCompleted);
        assertTrue(drones.get(1).busy);
    }


    /**
     * Confirms that DRONE_STATUS messages correctly update the scheduler’s
     * internal DroneInfo record, including state, zone, agent level, and
     * dispatchability flags.
     */
    @Test
    void schedulerDroneStatusCheck() throws Exception {
        registerDrone(1, drone1Socket);

        DroneStatus enRoute = new DroneStatus(1, DroneState.EN_ROUTE, 3, 100);
        Message statusMsg = Message.droneStatus(1, enRoute);

        scheduler.handle(statusMsg, InetAddress.getLoopbackAddress(), drone1Socket.getLocalPort());

        Map<Integer, DroneInfo> drones = getDroneMap();
        DroneInfo drone = drones.get(1);

        assertNotNull(drone);
        assertEquals(DroneState.EN_ROUTE, drone.lastKnownState);
        assertEquals(3, drone.currentZoneId);
        assertEquals(100, drone.remainingAgent);
        assertTrue(drone.busy);
        assertFalse(drone.available);
        assertFalse(drone.isDispatchable());
    }

    /**
     * Verifies that the scheduler sends a FIRE_EVENT ACK for each incoming
     * fire event received from the fire subsystem.
     */
    @Test
    void sendsFireAckIncomingFireEvent() throws Exception {
        registerDrone(1, drone1Socket);

        sendFireLine("14:03:15,3,FIRE_DETECTED,HIGH");
        sendFireLine("14:03:15,2,FIRE_DETECTED,LOW");

        Message ack1 = receiveMessage(fireSocket);
        Message ack2 = receiveMessage(fireSocket);

        assertFireAck(ack1);
        assertFireAck(ack2);
    }

    /**
     * Simulates a drone sending a DRONE_POLL message to register itself with
     * the scheduler and establish its UDP return address.
     *
     * @param droneId     the drone identifier
     * @param droneSocket the socket representing the drone
     */
    private void registerDrone(int droneId, DatagramSocket droneSocket) throws Exception {
        scheduler.handle(
                Message.dronePoll(droneId),
                InetAddress.getLoopbackAddress(),
                droneSocket.getLocalPort()
        );
    }

    /**
     * Parses a CSV fire-event line and sends it to the scheduler as a FIRE_EVENT
     * message from the fire subsystem.
     *
     * @param line CSV-formatted fire event
     */
    private void sendFireLine(String line) throws Exception {
        FireEvent event = fireParser.parseCSVLine(line);
        scheduler.handle(
                Message.fireEvent(event),
                InetAddress.getLoopbackAddress(),
                fireSocket.getLocalPort()
        );
    }

    /**
     * Receives a single UDP message from the given socket, failing the test
     * if no packet arrives before the socket timeout.
     *
     * @param socket the socket to read from
     * @return decoded Message object
     */
    private Message receiveMessage(DatagramSocket socket) throws Exception {
        byte[] buf = new byte[4096];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);

        try {
            socket.receive(packet);
        } catch (SocketTimeoutException e) {
            fail("Timed out waiting for UDP packet on port " + socket.getLocalPort());
        }

        byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
        return Message.fromBytes(data);
    }

    /**
     * Asserts that the given message is a FIRE_EVENT ACK (type FIRE_EVENT with
     * a null payload).
     *
     * @param msg the message to validate
     */
    private void assertFireAck(Message msg) {
        assertEquals(MessageType.FIRE_EVENT, msg.getType());
        assertNull(msg.getPayload());
    }

    /**
     * Returns the scheduler's pending event queue via reflection.
     *
     * @return the pendingEvents queue
     */
    @SuppressWarnings("unchecked")
    private Queue<FireEvent> getPendingQueue() throws Exception {
        Field f = SchedulerSubsystem.class.getDeclaredField("pendingEvents");
        f.setAccessible(true);
        return (Queue<FireEvent>) f.get(scheduler);
    }

    /**
     * Returns the number of pending fire events.
     */
    private int getPendingQueueSize() throws Exception {
        return getPendingQueue().size();
    }

    /**
     * Returns the scheduler's internal drone map via reflection.
     *
     * @return map of droneId → DroneInfo
     */
    @SuppressWarnings("unchecked")
    private Map<Integer, DroneInfo> getDroneMap() throws Exception {
        Field f = SchedulerSubsystem.class.getDeclaredField("drones");
        f.setAccessible(true);
        return (Map<Integer, DroneInfo>) f.get(scheduler);
    }

    /**
     * Returns the scheduler's current state via reflection.
     *
     * @return the SchedulerState value
     */
    private SchedulerState getSchedulerState() throws Exception {
        Field f = SchedulerSubsystem.class.getDeclaredField("schedulerState");
        f.setAccessible(true);
        return (SchedulerState) f.get(scheduler);
    }

    /**
     * Safely closes a DatagramSocket without throwing exceptions.
     *
     * @param socket the socket to close
     */
    private void closeQuietly(DatagramSocket socket) {
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }
}

