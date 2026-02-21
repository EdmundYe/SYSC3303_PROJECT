package tests;

import MessageTransport.MessageTransporter;
import MessageTransport.SendAddress;
import common.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class MessageTransporterTest {

    @Test
    void testSendReceiveSingleMessage() throws Exception {
        MessageTransporter mt = new MessageTransporter();
        FireEvent fe = new FireEvent(java.time.Instant.now(), 1, FireEventType.FIRE_DETECTED, Severity.LOW);
        Message m = Message.fireEvent(fe);

        mt.send(SendAddress.SCHEDULER, m);

        Message received = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> mt.receive(SendAddress.SCHEDULER));
        assertNotNull(received);
        assertEquals(MessageType.FIRE_EVENT, received.getType());
        assertTrue(received.getPayload() instanceof FireEvent);
        FireEvent receivedFe = (FireEvent) received.getPayload();
        assertEquals(1, receivedFe.getZoneId());
    }

    @Test
    void testQueueOrderPreserved() throws Exception {
        MessageTransporter mt = new MessageTransporter();
        Message m1 = Message.fireEvent(new FireEvent(java.time.Instant.now(), 1, FireEventType.FIRE_DETECTED, Severity.LOW));
        Message m2 = Message.fireEvent(new FireEvent(java.time.Instant.now(), 2, FireEventType.FIRE_DETECTED, Severity.MODERATE));

        mt.send(SendAddress.SCHEDULER, m1);
        mt.send(SendAddress.SCHEDULER, m2);

        Message r1 = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> mt.receive(SendAddress.SCHEDULER));
        Message r2 = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> mt.receive(SendAddress.SCHEDULER));

        assertEquals(m1.getPayload().getClass(), r1.getPayload().getClass());
        assertEquals(m2.getPayload().getClass(), r2.getPayload().getClass());
    }
}