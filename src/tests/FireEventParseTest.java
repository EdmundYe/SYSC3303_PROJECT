package tests;

import MessageTransport.MessageTransporter;
import SubSystems.FireIncidentSubsystem;
import common.FireEvent;
import common.FireEventType;
import common.Severity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FireEventParseTest {

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