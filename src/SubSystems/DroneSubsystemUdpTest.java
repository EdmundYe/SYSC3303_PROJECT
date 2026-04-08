package SubSystems;

import common.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class DroneSubsystemUdpTest {

    private DroneSubsystem drone;
    private DatagramSocket schedulerSocket;

    @BeforeEach
    void setUp() throws Exception {
        // Drone 1 binds to 6101 in your current code
        drone = new DroneSubsystem(1, null, null);

        // Drone sends polls/statuses to scheduler port 6000
        schedulerSocket = new DatagramSocket(6000);
        schedulerSocket.setSoTimeout(1500);
    }

    @AfterEach
    void tearDown() throws Exception {
        closeDroneSocket();
        closeQuietly(schedulerSocket);
    }

    @Test
    void dronePollTest() throws Exception {
        invokeNoArg("sendPoll");

        Message msg = receiveMessage(schedulerSocket);

        assertEquals(MessageType.DRONE_POLL, msg.getType());
        assertEquals(1, msg.get_source_id());
        assertEquals(1, msg.getPayload());
    }

    @Test
    void sendStatus_sendsDroneStatusPacketToScheduler() throws Exception {
        invokeSendStatus(DroneState.EN_ROUTE, 3, 90);

        Message msg = receiveMessage(schedulerSocket);

        assertEquals(MessageType.DRONE_STATUS, msg.getType());
        assertEquals(1, msg.get_source_id());

        DroneStatus status = (DroneStatus) msg.getPayload();
        assertEquals(1, status.get_drone_id());
        assertEquals(DroneState.EN_ROUTE, status.getState());
        assertEquals(3, status.get_zone_id());
        assertEquals(90, status.get_remaining_agent());
    }


    @Test
    void computeTravelTimeMs_increasesWithDistance() throws Exception {
        long zone1 = (long) invokeMethod("computeTravelTimeMs", new Class[]{double.class}, 500.0);
        long zone2 = (long) invokeMethod("computeTravelTimeMs", new Class[]{double.class}, 800.0);
        long zone3 = (long) invokeMethod("computeTravelTimeMs", new Class[]{double.class}, 1200.0);
        long zone4 = (long) invokeMethod("computeTravelTimeMs", new Class[]{double.class}, 1500.0);

        assertTrue(zone1 > 0);
        assertTrue(zone2 > zone1);
        assertTrue(zone3 > zone2);
        assertTrue(zone4 > zone3);
    }

    @Test
    void requiredAgentLitres_returnsExpectedAmounts() throws Exception {
        assertEquals(10, Severity.LOW.requiredAgentLitres());
        assertEquals(20, Severity.MODERATE.requiredAgentLitres());
        assertEquals(30, Severity.HIGH.requiredAgentLitres());
    }

    @Test
    void run_sendsRepeatedPollsUntilInterrupted() throws Exception {
        Thread droneThread = new Thread(drone, "Drone-Test-Thread");
        droneThread.start();

        Message first = receiveMessage(schedulerSocket);
        Message second = receiveMessage(schedulerSocket);

        assertEquals(MessageType.DRONE_POLL, first.getType());
        assertEquals(MessageType.DRONE_POLL, second.getType());

        droneThread.interrupt();
        droneThread.join(2000);

        assertFalse(droneThread.isAlive());
    }

    private void invokeNoArg(String methodName) throws Exception {
        Method m = DroneSubsystem.class.getDeclaredMethod(methodName);
        m.setAccessible(true);
        m.invoke(drone);
    }

    private void invokeSendStatus(DroneState state, Integer zoneId, Integer remainingAgent) throws Exception {
        Method m = DroneSubsystem.class.getDeclaredMethod(
                "sendStatus", DroneState.class, Integer.class, Integer.class
        );
        m.setAccessible(true);
        m.invoke(drone, state, zoneId, remainingAgent);
    }

    private Object invokeMethod(String methodName, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method m = DroneSubsystem.class.getDeclaredMethod(methodName, parameterTypes);
        m.setAccessible(true);
        return m.invoke(drone, args);
    }

    private DroneState getDroneState() throws Exception {
        Field f = DroneSubsystem.class.getDeclaredField("state");
        f.setAccessible(true);
        return (DroneState) f.get(drone);
    }

    private void closeDroneSocket() throws Exception {
        Field f = DroneSubsystem.class.getDeclaredField("socket");
        f.setAccessible(true);
        DatagramSocket socket = (DatagramSocket) f.get(drone);
        closeQuietly(socket);
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

    private void closeQuietly(DatagramSocket socket) {
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }
}