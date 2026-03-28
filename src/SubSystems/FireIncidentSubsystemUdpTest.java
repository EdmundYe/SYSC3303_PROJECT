package SubSystems;

import common.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class FireIncidentSubsystemUdpTest {

    private FireIncidentSubsystem fireSubsystem;
    private DatagramSocket fireSocket;
    private DatagramSocket senderSocket;

    @BeforeEach
    void setUp() throws Exception {
        fireSubsystem = new FireIncidentSubsystem("src/input.csv");

        fireSocket = new DatagramSocket(0);
        fireSocket.setSoTimeout(1000);

        senderSocket = new DatagramSocket();

        setField("socket", fireSocket);
        setField("outstandingFires", 0);
    }

    @AfterEach
    void tearDown() {
        closeQuietly(fireSocket);
        closeQuietly(senderSocket);
    }

    @Test
    void parseCSVLine_parsesZoneEventTypeAndSeverity() {
        FireEvent event = fireSubsystem.parseCSVLine("14:03:15,3,FIRE_DETECTED,HIGH");

        assertEquals(3, event.getZoneId());
        assertEquals(FireEventType.FIRE_DETECTED, event.getEventType());
        assertEquals(Severity.HIGH, event.getSeverity());
        assertNotNull(event.getTimestamp());
    }

    @Test
    void parseCSVLine_handlesWhitespaceAroundCommas() {
        FireEvent event = fireSubsystem.parseCSVLine("14:03:15 , 2 , FIRE_DETECTED , LOW");

        assertEquals(2, event.getZoneId());
        assertEquals(FireEventType.FIRE_DETECTED, event.getEventType());
        assertEquals(Severity.LOW, event.getSeverity());
    }

    @Test
    void waitForFireOut_decrementsOutstandingWhenFireOutArrives() throws Exception {
        setField("outstandingFires", 3);

        FireEvent fireOutEvent = new FireEvent(
                Instant.now(),
                1,
                FireEventType.FIRE_DETECTED,
                Severity.MODERATE,
                FaultType.NONE,
                0
        );

        Thread sender = new Thread(() -> {
            try {
                Thread.sleep(100);
                sendToFireSocket(Message.fireOut(fireOutEvent));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        sender.start();
        invokeNoArg("waitForFireOut");
        sender.join();

        int outstanding = (int) getField("outstandingFires");
        assertEquals(2, outstanding);
    }


    private void sendToFireSocket(Message msg) throws Exception {
        byte[] data = msg.toBytes();
        DatagramPacket packet = new DatagramPacket(
                data,
                data.length,
                InetAddress.getLoopbackAddress(),
                fireSocket.getLocalPort()
        );
        senderSocket.send(packet);
    }

    private void invokeNoArg(String methodName) throws Exception {
        Method method = FireIncidentSubsystem.class.getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(fireSubsystem);
    }

    private void setField(String fieldName, Object value) throws Exception {
        Field field = FireIncidentSubsystem.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(fireSubsystem, value);
    }

    private Object getField(String fieldName) throws Exception {
        Field field = FireIncidentSubsystem.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(fireSubsystem);
    }

    private void closeQuietly(DatagramSocket socket) {
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }
}