package SubSystems;

import MessageTransport.MessageTransporter;
import MessageTransport.SendAddress;
import common.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class DroneSubsystemTest {
    /**
     * Tests how the drone starts and polls
     * Expected result: Drone receives a DRONE_POLL message from scheduler with droneId as payload
     * @throws Exception
     */
    @Test
    void testRun() throws Exception{
        MessageTransporter testTransporter = new MessageTransporter();
        int droneId = 1;
        DroneSubsystem testDrone = new DroneSubsystem(droneId, testTransporter);

        Thread droneThread = new Thread(testDrone);
        droneThread.start();

        try{
            Message response = assertTimeoutPreemptively(Duration.ofMillis(500), () -> testTransporter.receive(SendAddress.SCHEDULER));
            // check if drone receives message from scheduler
            assertEquals(MessageType.DRONE_POLL, response.getType());
            assertEquals(droneId, (int) response.getPayload());
        } finally {
            droneThread.interrupt();
            droneThread.join();
        }
    }

    /**
     * Tests how the drone responds to recieving a message from the scheduler
     * Expected result: response contains print messages aligned with successful execution.
     * @throws Exception
     */
    @Test
    void testDroneTask() throws Exception{
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer));

        MessageTransporter testTransporter = new MessageTransporter();
        DroneSubsystem testDrone = new DroneSubsystem(1, testTransporter);
        int droneId = 1;

        Thread droneThread = new Thread(testDrone);
        droneThread.start();
        try{
            Thread.sleep(500);
            DroneCommand command = new DroneCommand(
                    "1",
                    DroneCommandOptions.DISPATCH,
                    3,
                    Severity.HIGH
            );
            testTransporter.send(SendAddress.DRONE, Message.droneTask(droneId, command));
            // sleep for 10 secs to allow drone to complete task
            Thread.sleep(10000);
            String response = buffer.toString();
            // check response from print stream buffer to see if responses line up with correct behaviour
            assertTrue(response.contains("[DRONE " + droneId + "] Received task"));
            assertTrue(response.contains("[DRONE " + droneId + "] Task completed and reported"), "Expected DRONE_TASK print not found, test failed");
        } finally {
            droneThread.interrupt();
            droneThread.join();
        }
    }

    /**
     * Tests how the drones behaviour when given a command
     * Expected result: response message contains printed messages as if simulating a command execution
     * @throws Exception
     */
    @Test
    void testExecuteCommand() throws Exception{
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer));

        MessageTransporter testTransporter = new MessageTransporter();
        DroneSubsystem testDrone = new DroneSubsystem(1, testTransporter);

        Thread droneThread = new Thread(testDrone);
        droneThread.start();
        try{
            DroneCommand testCommand = new DroneCommand("1", DroneCommandOptions.DISPATCH, 1, Severity.HIGH);
            int droneId = 1;
            testTransporter.send(SendAddress.DRONE, Message.droneTask(droneId, testCommand));

            // sleep for 10 secs to allow threads to communicate
            Thread.sleep(10000);
            String response = buffer.toString();
            // check response from print stream buffer to see if responses line up with correct behaviour
            assertTrue(response.contains("[DRONE " + droneId + "] Dispatching to zone " + testCommand.get_zone_id()), "Dispatch message missing, test failed");
            assertTrue(response.contains("[DRONE " + droneId + "] En route"), "En route message missing, test failed");
            assertTrue(response.contains("[DRONE " + droneId + "] Dropping agent (" + testCommand.getSeverity() + ")"), "Payload drop message missing, test failed");
            assertTrue(response.contains("[DRONE " + droneId + "] Returning to base"), "Drone returning message missing, test failed.");
        } finally {
            droneThread.interrupt();
            droneThread.join();
        }
    }
}