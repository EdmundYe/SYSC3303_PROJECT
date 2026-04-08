package tests;

import MessageTransport.MessageTransporter;
import MessageTransport.SendAddress;
import SubSystems.DroneSubsystem;
import common.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.DatagramSocket;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class DroneSubsystemTest {
    private DroneSubsystem drone;

    /**
     * Creates a new DroneSubsystem before each test.
     * A fixed drone ID is used so the bound port (6101)
     * is predictable and consistent across tests.
     */
    @BeforeEach
    void setUp() {
        drone = new DroneSubsystem(1);
    }

    /**
     * Ensures the socket inside DroneSubsystem is closed
     * after each test to avoid binding to a port in use.
     */
    @AfterEach
    void cleanup() {
        try {
            Field f = DroneSubsystem.class.getDeclaredField("socket");
            f.setAccessible(true);
            DatagramSocket s = (DatagramSocket) f.get(drone);
            s.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Invokes a private method on DroneSubsystem using reflection.
     */
    private Object invokePrivate(Object target, String name, Class<?>[] paramTypes, Object... args) {
        try {
            Method m = target.getClass().getDeclaredMethod(name, paramTypes);
            m.setAccessible(true);
            return m.invoke(target, args);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Sets the drone's internal state via reflection.
     */
    private void setState(DroneState state) {
        try {
            Field f = DroneSubsystem.class.getDeclaredField("state");
            f.setAccessible(true);
            f.set(drone, state);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Retrieves the drone's internal state via reflection.
     */
    private DroneState getState() {
        try {
            Field f = DroneSubsystem.class.getDeclaredField("state");
            f.setAccessible(true);
            return (DroneState) f.get(drone);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Retrieves the drone's remaining firefighting agent (litres).
     */
    private int getRemainingAgent() {
        try {
            Field f = DroneSubsystem.class.getDeclaredField("remainingAgent");
            f.setAccessible(true);
            return (int) f.get(drone);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Verifies that the agent usage per severity level matches
     *   LOW = 10L
     *   MODERATE = 20L
     *   HIGH = 30L
     */
    @Test
    void agentUsageForSeverity_matchesSpec() {
        int low = (int) invokePrivate(drone, "agentUsageForSeverity",
                new Class<?>[]{Severity.class}, Severity.LOW);

        int moderate = (int) invokePrivate(drone, "agentUsageForSeverity",
                new Class<?>[]{Severity.class}, Severity.MODERATE);

        int high = (int) invokePrivate(drone, "agentUsageForSeverity",
                new Class<?>[]{Severity.class}, Severity.HIGH);

        assertEquals(10, low);
        assertEquals(20, moderate);
        assertEquals(30, high);
    }

    /**
     * Ensures drop‑time calculation respects the configured drop rate.
     */
    @Test
    void computeDropTimeMs_respectsWaterDropRate() {
        long ms = (long) invokePrivate(drone, "computeDropTimeMs",
                new Class<?>[]{int.class}, 10);

        assertEquals(50_000L, ms);
    }

    /**
     * Ensures fractional drop durations are rounded up.
     */
    @Test
    void computeDropTimeMs_roundsUpForFractionalSeconds() {
        long ms = (long) invokePrivate(drone, "computeDropTimeMs",
                new Class<?>[]{int.class}, 1);

        assertEquals(5_000L, ms);
    }

    /**
     * Travel time for zero distance should always return at least 1 ms.
     */
    @Test
    void computeTravelTimeMs_zeroDistanceReturnsMinimumOneMs() {
        long ms = (long) invokePrivate(drone, "computeTravelTimeMs",
                new Class<?>[]{double.class}, 0.0);

        assertEquals(1L, ms);
    }

    /**
     * Travel time for any positive distance must be > 0.
     */
    @Test
    void computeTravelTimeMs_positiveDistanceIsPositive() {
        long ms = (long) invokePrivate(drone, "computeTravelTimeMs",
                new Class<?>[]{double.class}, 1000.0);

        assertTrue(ms > 0);
    }

    /**
     * TASK_RECEIVED should move the drone out of IDLE.
     */
    @Test
    void transition_taskReceived_movesIdleToEnRouteOrBusy() {
        setState(DroneState.IDLE);

        invokePrivate(drone, "transition",
                new Class<?>[]{DroneEvent.class}, DroneEvent.TASK_RECEIVED);

        assertNotEquals(DroneState.IDLE, getState());
    }

    /**
     * FAULT_DETECTED followed by HARD_FAULT should place the drone
     * into the OFFLINE state, representing a non‑recoverable failure.
     */
    @Test
    void transition_faultDetected_thenHardFault_leadsToOffline() {
        setState(DroneState.EN_ROUTE);

        invokePrivate(drone, "transition",
                new Class<?>[]{DroneEvent.class}, DroneEvent.FAULT_DETECTED);

        invokePrivate(drone, "transition",
                new Class<?>[]{DroneEvent.class}, DroneEvent.HARD_FAULT);

        assertEquals(DroneState.OFFLINE, getState());
    }

    /**
     * RECOVERED should move the drone out of FAULTED state.
     */
    @Test
    void transition_recoveredFromFault_returnsToIdleOrReturning() {
        setState(DroneState.FAULTED);

        invokePrivate(drone, "transition",
                new Class<?>[]{DroneEvent.class}, DroneEvent.RECOVERED);

        assertNotEquals(DroneState.FAULTED, getState());
    }

    /**
     * Verifies that a HARD_FAULT places the drone OFFLINE and does not
     * modify the remaining agent level.
     */
    @Test
    void hardFaultLeavesDroneOfflineAndAgentUnchanged() {
        int beforeAgent = getRemainingAgent();
        setState(DroneState.EN_ROUTE);

        invokePrivate(drone, "transition",
                new Class<?>[]{DroneEvent.class}, DroneEvent.FAULT_DETECTED);

        invokePrivate(drone, "transition",
                new Class<?>[]{DroneEvent.class}, DroneEvent.HARD_FAULT);

        assertEquals(DroneState.OFFLINE, getState());
        assertEquals(beforeAgent, getRemainingAgent());
    }
    /**
     * Tests how the drone starts and polls
     * Expected result: Drone receives a DRONE_POLL message from scheduler with droneId as payload
     * @throws Exception
     */
    @Deprecated
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
    @Deprecated
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
                    Severity.HIGH,
                    10
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
    @Deprecated
    void testExecuteCommand() throws Exception{
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer));

        MessageTransporter testTransporter = new MessageTransporter();
        DroneSubsystem testDrone = new DroneSubsystem(1, testTransporter);

        Thread droneThread = new Thread(testDrone);
        droneThread.start();
        try{
            DroneCommand testCommand = new DroneCommand("1", DroneCommandOptions.DISPATCH, 1, Severity.HIGH, 10);
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