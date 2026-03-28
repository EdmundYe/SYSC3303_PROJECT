package tests;

import SubSystems.*;
import common.*;
import MessageTransport.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;


import static org.junit.jupiter.api.Assertions.*;

class SchedulerDispatchIntegrationTest {


    @Test
    @Deprecated
    void testSchedulerDispatchesOnDronePoll() throws Exception {
        MessageTransporter mt = new MessageTransporter();
        SchedulerSubsystem scheduler = new SchedulerSubsystem(mt);
        Thread schedThread = new Thread(scheduler);
        schedThread.start();

        try {
            // create a fire event and send to scheduler
            FireEvent fe = new FireEvent(Instant.now(), 42, FireEventType.FIRE_DETECTED, Severity.MODERATE, FaultType.NONE, 0);
            mt.send(SendAddress.SCHEDULER, Message.fireEvent(fe));

            // simulate drone polling
            mt.send(SendAddress.SCHEDULER, Message.dronePoll(1));

            // scheduler should send a DRONE_TASK to DRONE address
            Message out = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> mt.receive(SendAddress.DRONE));
            assertEquals(MessageType.DRONE_TASK, out.getType());
            assertTrue(out.getPayload() instanceof common.DroneCommand);
            common.DroneCommand cmd = (common.DroneCommand) out.getPayload();
            assertEquals(42, cmd.get_zone_id());
            assertEquals(Severity.MODERATE, cmd.getSeverity());

        } finally {
            schedThread.interrupt();
            schedThread.join(1000);
        }
    }

    @Test
    @Deprecated
    void testSchedulerHandlesMultipleEventsFIFO() throws Exception {
        MessageTransporter mt = new MessageTransporter();
        SchedulerSubsystem scheduler = new SchedulerSubsystem(mt);
        Thread schedThread = new Thread(scheduler);
        schedThread.start();

        try {
            FireEvent fe1 = new FireEvent(Instant.now(), 1, FireEventType.FIRE_DETECTED, Severity.LOW, FaultType.NONE, 0);
            FireEvent fe2 = new FireEvent(Instant.now(), 2, FireEventType.FIRE_DETECTED, Severity.LOW, FaultType.NONE, 0);

            mt.send(SendAddress.SCHEDULER, Message.fireEvent(fe1));
            mt.send(SendAddress.SCHEDULER, Message.fireEvent(fe2));

            // first poll -> should dispatch fe1
            mt.send(SendAddress.SCHEDULER, Message.dronePoll(1));
            Message first = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> mt.receive(SendAddress.DRONE));
            assertEquals(MessageType.DRONE_TASK, first.getType());
            common.DroneCommand cmd1 = (common.DroneCommand) first.getPayload();
            assertEquals(1, cmd1.get_zone_id());

            // simulate done for first
            common.DroneStatus done = new common.DroneStatus(1, common.DroneState.DONE, cmd1.get_zone_id(), null);
            mt.send(SendAddress.SCHEDULER, Message.droneDone(1, done));

            // next poll -> should dispatch fe2
            mt.send(SendAddress.SCHEDULER, Message.dronePoll(1));
            Message second = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> mt.receive(SendAddress.DRONE));
            common.DroneCommand cmd2 = (common.DroneCommand) second.getPayload();
            assertEquals(2, cmd2.get_zone_id());

        } finally {
            schedThread.interrupt();
            schedThread.join(1000);
        }
    }
}