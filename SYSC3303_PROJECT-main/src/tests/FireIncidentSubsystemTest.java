package tests;

import MessageTransport.MessageTransporter;
import MessageTransport.SendAddress;
import SubSystems.FireIncidentSubsystem;
import common.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class FireIncidentSubsystemTest {
    /**
     * Tests how the Fire Incident system reads CSV files
     * Expected result: dummy event is filled using CSV lines that mimic a CSV file
     * @throws Exception
     */
    @Test
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

    /**
     * Tests how the scheduler and fire incident systems communicate
     * Expected result: Response contains print statements that confirm communication between both systems
     * @throws Exception
     */
    @Test
    void testRun() throws Exception{
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer));

        MessageTransporter testTransporter = new MessageTransporter();
        FireIncidentSubsystem testFireIncident = new FireIncidentSubsystem(testTransporter, "src/input.csv");
        Thread fireIncidentThread = new Thread(testFireIncident);
        int droneId = 1;

        fireIncidentThread.start();

        try{
            Message testMessage = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> testTransporter.receive(SendAddress.SCHEDULER));
            assertEquals(MessageType.FIRE_EVENT, testMessage.getType());

            FireEvent testEvent = (FireEvent) testMessage.getPayload();
            // check if test event exists, indicates that message is received from scheduler
            assertNotNull(testEvent);

            testTransporter.send(SendAddress.FIRE_INCIDENT, Message.dronePoll(droneId));

            Thread.sleep(500);
            String response = buffer.toString();

            // check response from print stream buffer to see if responses line up with correct behaviour
            assertTrue(response.contains("[FIRE] Sent FIRE_EVENT to Scheduler"), "FIRE_EVENT was not sent to scheduler, test failed");
            assertTrue(response.contains("[FIRE] Received response: "), "scheduler did not receive message, test failed");

        } finally {
            fireIncidentThread.interrupt();
            fireIncidentThread.join();
        }
    }
}