package tests;

import MessageTransport.MessageTransporter;
import MessageTransport.SendAddress;
import SubSystems.DroneSubsystem;
import common.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class DroneIntegrationTest {

    /**
     * Integration test:
     * - Start drone thread
     * - Verify scheduler receives DRONE_POLL
     * - Send DRONE_TASK to drone and expect DRONE_DONE reported back to SCHEDULER
     *
     * Note: DroneSubsystem uses Thread.sleep in executeCommand; allow generous timeout.
     */
    @Test
    void testDronePollAndTaskExecution() throws Exception {
        MessageTransporter mt = new MessageTransporter();
        DroneSubsystem drone = new DroneSubsystem(1, mt);
        Thread droneThread = new Thread(drone);
        droneThread.start();

        try {
            // First, the drone should poll the scheduler (initial poll)
            Message poll = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> mt.receive(SendAddress.SCHEDULER));
            assertEquals(MessageType.DRONE_POLL, poll.getType());
            assertEquals(1, (int) poll.getPayload());

            // Send a task to the drone
            DroneCommand cmd = new DroneCommand("REQ-1", DroneCommandOptions.DISPATCH, 7, Severity.HIGH);
            mt.send(SendAddress.DRONE, Message.droneTask(1, cmd));

            // Wait for DRONE_DONE: keep receiving messages and ignore non-DRONE_DONE ones until timeout.
            Message done = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
                while (true) {
                    Message m = mt.receive(SendAddress.SCHEDULER);
                    if (m.getType() == MessageType.DRONE_DONE) {
                        return m;
                    }
                    // otherwise ignore (e.g., DRONE_POLL) and continue waiting
                }
            });

            assertEquals(MessageType.DRONE_DONE, done.getType());
            assertTrue(done.getPayload() instanceof DroneStatus);
            DroneStatus status = (DroneStatus) done.getPayload();
            assertEquals(1, status.get_drone_id());
            assertNotNull(status.getState());

        } finally {
            droneThread.interrupt();
            droneThread.join(1000);
        }
    }
}