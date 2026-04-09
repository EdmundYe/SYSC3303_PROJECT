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

// Unit tests for FireIncidentSubsystem's UDP communication
class FireIncidentSubsystemUdpTest {

    private FireIncidentSubsystem fireSubsystem;
    private DatagramSocket fireSocket;
    private DatagramSocket senderSocket;

    /**
     * Initializes the subsystem and replaces its internal socket with a
     * test‑controlled DatagramSocket. Also resets outstanding fire count.
     */
    @BeforeEach
    void setUp() throws Exception {
        fireSubsystem = new FireIncidentSubsystem("src/input.csv");

        fireSocket = new DatagramSocket(0);
        fireSocket.setSoTimeout(1000);

        senderSocket = new DatagramSocket();

        setField("socket", fireSocket);
        setField("outstandingFires", 0);
    }

    /**
     * Closes all sockets after each test.
     */
    @AfterEach
    void tearDown() {
        closeQuietly(fireSocket);
        closeQuietly(senderSocket);
    }

    /**
     * Ensures parseCSVLine() extracts zone, event type, severity,
     * and timestamp from a standard CSV line.
     */
    @Test
    void parseCSVLine_parsesZoneEventTypeAndSeverity() {
        FireEvent event = fireSubsystem.parseCSVLine("14:03:15,3,FIRE_DETECTED,HIGH");

        assertEquals(3, event.getZoneId());
        assertEquals(FireEventType.FIRE_DETECTED, event.getEventType());
        assertEquals(Severity.HIGH, event.getSeverity());
        assertNotNull(event.getTimestamp());
    }

    /**
     * Ensures parseCSVLine() tolerates whitespace around commas.
     */
    @Test
    void parseCSVLine_handlesWhitespaceAroundCommas() {
        FireEvent event = fireSubsystem.parseCSVLine("14:03:15 , 2 , FIRE_DETECTED , LOW");

        assertEquals(2, event.getZoneId());
        assertEquals(FireEventType.FIRE_DETECTED, event.getEventType());
        assertEquals(Severity.LOW, event.getSeverity());
    }

    /**
     * Verifies that waitForFireOut() decrements outstandingFires
     * when a FIRE_OUT message arrives on the subsystem's socket.
     */
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


    /**
     * Sends a message directly to the subsystem's UDP socket.
     */
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

    /**
     * Invokes a private no‑argument method on the subsystem via reflection.
     */
    private void invokeNoArg(String methodName) throws Exception {
        Method method = FireIncidentSubsystem.class.getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(fireSubsystem);
    }

    /**
     * Sets a private field on the subsystem via reflection.
     */
    private void setField(String fieldName, Object value) throws Exception {
        Field field = FireIncidentSubsystem.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(fireSubsystem, value);
    }

    /**
     * Reads a private field from the subsystem via reflection.
     */
    private Object getField(String fieldName) throws Exception {
        Field field = FireIncidentSubsystem.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(fireSubsystem);
    }

    /**
     * Safely closes a DatagramSocket without throwing.
     */
    private void closeQuietly(DatagramSocket socket) {
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }
}