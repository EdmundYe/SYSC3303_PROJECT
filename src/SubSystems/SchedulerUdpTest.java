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

    @Test
    void dispatchesPendingFourthEventWhenDroneReportsDone() throws Exception {
        registerDrone(1, drone1Socket);
        registerDrone(2, drone2Socket);
        registerDrone(3, drone3Socket);

        sendFireLine("14:03:15,3,FIRE_DETECTED,HIGH,NONE,0");
        sendFireLine("14:03:15,2,FIRE_DETECTED,LOW,NONE,0");
        sendFireLine("14:03:15,1,FIRE_DETECTED,MODERATE,NONE,0");
        sendFireLine("14:03:15,4,FIRE_DETECTED,HIGH,NONE,0");

        for (int i = 0; i < 4; i++) {
            assertFireAck(receiveMessage(fireSocket));
        }

        receiveMessage(drone1Socket);
        receiveMessage(drone2Socket);
        receiveMessage(drone3Socket);

        assertEquals(3, getPendingQueueSize());

        DroneStatus doneStatus = new DroneStatus(1, DroneState.IDLE, null, 80);
        Message done = Message.droneDone(1, doneStatus);

        scheduler.handle(done, InetAddress.getLoopbackAddress(), drone1Socket.getLocalPort());

        Message fireOut = receiveMessage(fireSocket);
        assertEquals(MessageType.FIRE_OUT, fireOut.getType());
        assertNotNull(fireOut.getPayload());

        Message nextTask = receiveMessage(drone1Socket);
        assertEquals(MessageType.DRONE_TASK, nextTask.getType());

        DroneCommand cmd = (DroneCommand) nextTask.getPayload();
        assertEquals(4, cmd.get_zone_id());
        assertEquals(Severity.HIGH, cmd.getSeverity());

        assertEquals(FaultType.NONE, cmd.getFaultType());
        assertEquals(0, cmd.getFaultDelaySeconds());

        assertEquals(0, getPendingQueueSize());

        Map<Integer, DroneInfo> drones = getDroneMap();
        assertEquals(1, drones.get(1).missionsCompleted);
        assertTrue(drones.get(1).busy);
    }

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

    private void registerDrone(int droneId, DatagramSocket droneSocket) throws Exception {
        scheduler.handle(
                Message.dronePoll(droneId),
                InetAddress.getLoopbackAddress(),
                droneSocket.getLocalPort()
        );
    }

    private void sendFireLine(String line) throws Exception {
        FireEvent event = fireParser.parseCSVLine(line);
        scheduler.handle(
                Message.fireEvent(event),
                InetAddress.getLoopbackAddress(),
                fireSocket.getLocalPort()
        );
    }

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

    private void assertFireAck(Message msg) {
        assertEquals(MessageType.FIRE_EVENT, msg.getType());
        assertNull(msg.getPayload());
    }

    @SuppressWarnings("unchecked")
    private Queue<FireEvent> getPendingQueue() throws Exception {
        Field f = SchedulerSubsystem.class.getDeclaredField("pendingEvents");
        f.setAccessible(true);
        return (Queue<FireEvent>) f.get(scheduler);
    }

    private int getPendingQueueSize() throws Exception {
        return getPendingQueue().size();
    }

    @SuppressWarnings("unchecked")
    private Map<Integer, DroneInfo> getDroneMap() throws Exception {
        Field f = SchedulerSubsystem.class.getDeclaredField("drones");
        f.setAccessible(true);
        return (Map<Integer, DroneInfo>) f.get(scheduler);
    }

    private SchedulerState getSchedulerState() throws Exception {
        Field f = SchedulerSubsystem.class.getDeclaredField("schedulerState");
        f.setAccessible(true);
        return (SchedulerState) f.get(scheduler);
    }

    private void closeQuietly(DatagramSocket socket) {
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }
}

