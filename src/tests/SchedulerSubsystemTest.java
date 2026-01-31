package tests;

import MessageTransport.MessageTransporter;
import MessageTransport.SendAddress;
import SubSystems.SchedulerSubsystem;
import common.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;

import static common.FireEventType.FIRE_DETECTED;
import static common.Severity.HIGH;
import static org.junit.jupiter.api.Assertions.*;

class SchedulerSubsystemTest {
    /**
     * Tests how scheduler handles FIRE_EVENT
     * Expected result: response message confirms event is sent to events queue
     * @throws InterruptedException
     */
    @Test
    void testHandleFireEvent() throws InterruptedException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer));

        Instant timestamp = Instant.now();
        FireEventType testType = FIRE_DETECTED;
        Severity testSeverity = HIGH;

        MessageTransporter testTransporter = new MessageTransporter();
        SchedulerSubsystem testScheduler = new SchedulerSubsystem(testTransporter);

        Thread schedulerThread = new Thread(testScheduler);
        schedulerThread.start();
        try{
            FireEvent testEvent = new FireEvent(timestamp, 3, testType, testSeverity);
            testTransporter.send(SendAddress.SCHEDULER, Message.fireEvent(testEvent));
            Thread.sleep(100);

            String response = buffer.toString();
            // check response from print stream buffer to see if responses line up with correct behaviour
            assertTrue(response.contains("[SCHEDULER] Received FIRE_EVENT: "+ testEvent), "Expected FIRE_EVENT print line not found, test failed");

        } finally {
            schedulerThread.interrupt();
            schedulerThread.join(1000);
        }
    }

    /**
     * Tests how scheduler handles DRONE_POLL
     * Expected result: message type from drone response matches DRONE_TASK
     * @throws InterruptedException
     */
    @Test
    void testHandleDronePoll() throws InterruptedException{
        Instant timestamp = Instant.now();
        FireEventType testType = FIRE_DETECTED;
        Severity testSeverity = HIGH;

        MessageTransporter testTransporter = new MessageTransporter();
        SchedulerSubsystem testScheduler = new SchedulerSubsystem(testTransporter);

        Thread schedulerThread = new Thread(testScheduler);
        schedulerThread.start();
        try{
            FireEvent testEvent = new FireEvent(timestamp, 0, testType, testSeverity);
            testTransporter.send(SendAddress.SCHEDULER, Message.fireEvent(testEvent));
            testTransporter.send(SendAddress.SCHEDULER, Message.dronePoll(1));

            Message response = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> testTransporter.receive(SendAddress.DRONE));
            // check if drone receives drone task message from scheduler
            assertEquals(MessageType.DRONE_TASK, response.getType());

        } finally {
            schedulerThread.interrupt();
            schedulerThread.join(1000);
        }
    }

    /**
     * Tests how scheduler handles DRONE_DONE
     * Expected result: response message confirms drone task complete
     * @throws InterruptedException
     */
    @Test
    void testHandleDroneDone() throws InterruptedException{
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer));

        MessageTransporter testTransporter = new MessageTransporter();
        SchedulerSubsystem testScheduler = new SchedulerSubsystem(testTransporter);

        Thread schedulerThread = new Thread(testScheduler);
        schedulerThread.start();
        try{
            DroneStatus testStatus = new DroneStatus(1, DroneState.DONE, 3, null);
            testTransporter.send(SendAddress.SCHEDULER, Message.droneDone(1, testStatus));
            Thread.sleep(1000);

            String response = buffer.toString();
            // check response from print stream buffer to see if responses line up with correct behaviour
            assertTrue(response.contains("[SCHEDULER] Drone completed task: " + testStatus), "Expected DRONE_DONE print line not found, test failed");

        } finally {
            schedulerThread.interrupt();
            schedulerThread.join(1000);
        }
    }
}