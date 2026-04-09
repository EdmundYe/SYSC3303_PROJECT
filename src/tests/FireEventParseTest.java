package tests;

import MessageTransport.MessageTransporter;
import SubSystems.FireIncidentSubsystem;
import common.FireEvent;
import common.FireEventType;
import common.Severity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CSV fire‑event parsing
 */
class FireEventParseTest {

    /**
     * Ensures that a well‑formed CSV line is parsed into a FireEvent with the
     * correct timestamp, zone ID, event type, and severity.
     */
    @Test
    void testParseCSVLine() {
        MessageTransporter mt = new MessageTransporter();
        FireIncidentSubsystem fis = new FireIncidentSubsystem(mt, "does-not-matter.csv");

        FireEvent fe = fis.parseCSVLine("09:00:00, 1, FIRE_DETECTED, HIGH");

        assertNotNull(fe.getTimestamp());
        assertEquals(1, fe.getZoneId());
        assertEquals(FireEventType.FIRE_DETECTED, fe.getEventType());
        assertEquals(Severity.HIGH, fe.getSeverity());
    }

    /**
     * Verifies that the parser tolerates extra whitespace around CSV fields and
     * still extracts the correct zone ID, event type, and severity.
     */
    @Test
    void testParseCSVWhitespaceTolerance() {
        MessageTransporter mt = new MessageTransporter();
        FireIncidentSubsystem fis = new FireIncidentSubsystem(mt, "does-not-matter.csv");

        FireEvent fe = fis.parseCSVLine(" 09:00:00 ,  2 , DRONE_REQUEST , MODERATE ");

        assertEquals(2, fe.getZoneId());
        assertEquals(FireEventType.DRONE_REQUEST, fe.getEventType());
        assertEquals(Severity.MODERATE, fe.getSeverity());
    }
}