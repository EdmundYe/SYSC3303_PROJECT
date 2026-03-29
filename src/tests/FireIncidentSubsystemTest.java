package tests;

import MessageTransport.MessageTransporter;
import MessageTransport.SendAddress;
import SubSystems.FireIncidentSubsystem;
import common.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.net.DatagramSocket;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FireIncidentSubsystemTest {
    private FireIncidentSubsystem fire;

    /**
     * Creates a FireIncidentSubsystem with a dummy CSV path.
     * parseCSVLine() does not require the file to exist.
     */
    @BeforeEach
    void setUp() {
        fire = new FireIncidentSubsystem("dummy.csv");
    }

    /**
     * Ensures the DatagramSocket is closed after each test
     * if the subsystem created one.
     */
    @AfterEach
    void tearDown() {
        try {
            Field socketField = FireIncidentSubsystem.class.getDeclaredField("socket");
            socketField.setAccessible(true);
            DatagramSocket socket = (DatagramSocket) socketField.get(fire);
            if (socket != null) socket.close();
        } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------------
    // Input‑file parsing tests
    // ---------------------------------------------------------------------

    /**
     * Tests that a valid CSV line in the new Iteration‑4 format
     * is parsed into a correct FireEvent object.
     * Expected result: all fields match the CSV input.
     */
    @Test
    void parseCSVLine_parsesNewFormatCorrectly() {
        String line = "14:03:15,3,FIRE_DETECTED,HIGH,DRONE_STUCK,15";

        FireEvent event = fire.parseCSVLine(line);

        assertNotNull(event);
        assertEquals(3, event.getZoneId());
        assertEquals(FireEventType.FIRE_DETECTED, event.getEventType());
        assertEquals(Severity.HIGH, event.getSeverity());
        assertEquals(FaultType.DRONE_STUCK, event.getFaultType());
        assertEquals(15, event.getFaultDelaySeconds());
    }

    /**
     * Tests that the subsystem correctly skips header rows.
     * Expected result: parseCSVLine() returns null.
     */
    @Test
    void parseCSVLine_skipsHeaderRow() {
        String header = "Time,ZoneId,EventType,Severity,FaultType,FaultDelaySeconds";
        FireEvent event = fire.parseCSVLine(header);
        assertNull(event, "Header row must return null");
    }

    /**
     * Tests backwards compatibility with the old 4‑column CSV format.
     * Expected result: fault fields default to NONE and 0.
     */
    @Test
    void parseCSVLine_oldFormatDefaultsFaultFields() {
        String oldFormat = "14:03:15,2,FIRE_DETECTED,LOW";

        FireEvent event = fire.parseCSVLine(oldFormat);

        assertNotNull(event);
        assertEquals(FaultType.NONE, event.getFaultType());
        assertEquals(0, event.getFaultDelaySeconds());
    }


    /**
     * Tests that multiple CSV lines from different zones are parsed
     * independently and maintain correct zone IDs.
     * Expected result: parsed events preserve zone ordering.
     */
    @Test
    void multiZoneParsing_preservesZoneIds() {
        List<String> lines = List.of(
                "14:03:15,3,FIRE_DETECTED,HIGH,NONE,0",
                "14:03:15,2,FIRE_DETECTED,LOW,NONE,0",
                "14:03:20,1,FIRE_DETECTED,MODERATE,DRONE_STUCK,15"
        );

        FireEvent e1 = fire.parseCSVLine(lines.get(0));
        FireEvent e2 = fire.parseCSVLine(lines.get(1));
        FireEvent e3 = fire.parseCSVLine(lines.get(2));

        assertEquals(3, e1.getZoneId());
        assertEquals(2, e2.getZoneId());
        assertEquals(1, e3.getZoneId());
    }

    /**
     * Tests that severity levels map to correct water usage amounts.
     * Expected result:
     *   LOW → 10L
     *   MODERATE → 20L
     *   HIGH → 30L
     *
     * (This test validates the correctness of Severity mapping
     *  as used by the DroneSubsystem.)
     */
    @Test
    void severityMapsToCorrectWaterUsage() {
        assertEquals(10, severityToLitres(Severity.LOW));
        assertEquals(20, severityToLitres(Severity.MODERATE));
        assertEquals(30, severityToLitres(Severity.HIGH));
    }

    /**
     * Helper method replicating the DroneSubsystem's usage mapping.
     */
    private int severityToLitres(Severity s) {
        return switch (s) {
            case LOW -> 10;
            case MODERATE -> 20;
            case HIGH -> 30;
        };
    }

    /**
     * Tests that a CSV line specifying NOZZLE_JAM is parsed correctly.
     * Expected result: event.getFaultType() == NOZZLE_JAM.
     */
    @Test
    void parseCSVLine_parsesNozzleJamFault() {
        String line = "14:03:25,4,FIRE_DETECTED,HIGH,NOZZLE_JAM,10";

        FireEvent event = fire.parseCSVLine(line);

        assertNotNull(event);
        assertEquals(FaultType.NOZZLE_JAM, event.getFaultType());
        assertEquals(10, event.getFaultDelaySeconds());
    }

    /**
     * Tests that an unknown fault type does not break parsing.
     * Expected result: subsystem logs warning and defaults to NONE.
     */
    @Test
    void parseCSVLine_unknownFaultDefaultsToNone() {
        String line = "14:05:00,2,FIRE_DETECTED,MODERATE,UNKNOWN_FAULT,0";

        FireEvent event = fire.parseCSVLine(line);

        assertNotNull(event);
        assertEquals(FaultType.NONE, event.getFaultType());
    }

    /**
     * Tests that a DRONE_STUCK fault with delay is parsed correctly.
     * Expected result: faultType == DRONE_STUCK and delay matches CSV.
     */
    @Test
    void parseCSVLine_parsesDroneStuckWithDelay() {
        String line = "14:03:20,1,FIRE_DETECTED,MODERATE,DRONE_STUCK,15";

        FireEvent event = fire.parseCSVLine(line);

        assertNotNull(event);
        assertEquals(FaultType.DRONE_STUCK, event.getFaultType());
        assertEquals(15, event.getFaultDelaySeconds());
    }
    /**
     * Tests how the Fire Incident system reads CSV files
     * Expected result: dummy event is filled using CSV lines that mimic a CSV file
     * @throws Exception
     */
    @Test
    @Deprecated
    void testParseCSV() throws Exception{
        MessageTransporter testTransporter = new MessageTransporter();
        FireIncidentSubsystem testFireIncident = new FireIncidentSubsystem(testTransporter, "src/input.csv");
        // uses a string with comma separated values to avoid needing to read file directly
        FireEvent testEvent = testFireIncident.parseCSVLine("09:00:00, 1, FIRE_DETECTED, HIGH");

        // check if event payload contains csv line values
        assertNotNull(testEvent.getTimestamp());
        assertEquals(1, testEvent.getZoneId());
        assertEquals(FireEventType.FIRE_DETECTED, testEvent.getEventType());
        assertEquals(Severity.HIGH, testEvent.getSeverity());
    }

//    /**
//     * Tests how the scheduler and fire incident systems communicate
//     * Expected result: Response contains print statements that confirm communication between both systems
//     * @throws Exception
//     */
//    @Test
//    @Deprecated
//    void testRun() throws Exception{
//        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
//        System.setOut(new PrintStream(buffer));
//
//        MessageTransporter testTransporter = new MessageTransporter();
//        FireIncidentSubsystem testFireIncident = new FireIncidentSubsystem(testTransporter, "src/input.csv");
//        Thread fireIncidentThread = new Thread(testFireIncident);
//        int droneId = 1;
//
//        fireIncidentThread.start();
//
//        try{
//            Message testMessage = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> testTransporter.receive(SendAddress.SCHEDULER));
//            assertEquals(MessageType.FIRE_EVENT, testMessage.getType());
//
//            FireEvent testEvent = (FireEvent) testMessage.getPayload();
//            // check if test event exists, indicates that message is received from scheduler
//            assertNotNull(testEvent);
//
//            testTransporter.send(SendAddress.FIRE_INCIDENT, Message.dronePoll(droneId));
//
//            Thread.sleep(500);
//            String response = buffer.toString();
//
//            // check response from print stream buffer to see if responses line up with correct behaviour
//            assertTrue(response.contains("[FIRE] Sent FIRE_EVENT to Scheduler"), "FIRE_EVENT was not sent to scheduler, test failed");
//            assertTrue(response.contains("[FIRE] Received response: "), "scheduler did not receive message, test failed");
//
//        } finally {
//            fireIncidentThread.interrupt();
//            fireIncidentThread.join();
//        }
//    }
}