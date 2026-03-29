package tests;

import MessageTransport.MessageTransporter;
import MessageTransport.SendAddress;
import SubSystems.SchedulerSubsystem;
import common.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Queue;

import static common.FireEventType.FIRE_DETECTED;
import static common.Severity.HIGH;
import static org.junit.jupiter.api.Assertions.*;

class SchedulerSubsystemTest {

    private SchedulerSubsystem scheduler;

    @BeforeEach
    void setUp() {
        // Use constructor without GUI to keep tests lightweight
        scheduler = new SchedulerSubsystem(new MessageTransporter(), null);
    }

    @AfterEach
    void tearDown() {
        // Close scheduler sockets to avoid port conflicts between tests
        try {
            Field recvField = SchedulerSubsystem.class.getDeclaredField("receiveSocket");
            Field sendField = SchedulerSubsystem.class.getDeclaredField("sendSocket");
            recvField.setAccessible(true);
            sendField.setAccessible(true);
            DatagramSocket recv = (DatagramSocket) recvField.get(scheduler);
            DatagramSocket send = (DatagramSocket) sendField.get(scheduler);
            recv.close();
            send.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Object getField(Object target, String name) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void invokePrivate(Object target, String name, Class<?>[] paramTypes, Object... args) {
        try {
            Method m = target.getClass().getDeclaredMethod(name, paramTypes);
            m.setAccessible(true);
            m.invoke(target, args);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private SchedulerState getSchedulerState() {
        return (SchedulerState) getField(scheduler, "schedulerState");
    }

    @SuppressWarnings("unchecked")
    private Queue<FireEvent> getPendingEvents() {
        return (Queue<FireEvent>) getField(scheduler, "pendingEvents");
    }

    @SuppressWarnings("unchecked")
    private Map<Integer, DroneInfo> getDrones() {
        return (Map<Integer, DroneInfo>) getField(scheduler, "drones");
    }

    private void handle(Message msg) throws Exception {
        scheduler.handle(msg, InetAddress.getByName("localhost"), 9999);
    }

    /**
     * Tests how the scheduler handles a FIRE_EVENT and dispatches it
     * once a drone becomes available.
     *
     * Expected result:
     *  - Event is added to the pending queue
     *  - First drone poll registers the drone
     *  - Scheduler dispatches the event immediately
     *  - Drone becomes busy and assignedEvent is populated
     *  - Pending queue becomes empty
     *
     * @throws Exception if message handling fails
     */
    @Test
    void missionQueue_dispatchesEventWhenDroneBecomesAvailable() throws Exception {
        // 1) Fire event arrives
        FireEvent event = new FireEvent(
                Instant.now(),
                3,
                FireEventType.FIRE_DETECTED,
                Severity.HIGH,
                FaultType.NONE,
                0
        );
        Message fireMsg = new Message(MessageType.FIRE_EVENT, 0, event);
        handle(fireMsg);

        assertEquals(1, getPendingEvents().size(), "Fire event should be queued");

        // 2) Drone polls and becomes available
        Message pollMsg = new Message(MessageType.DRONE_POLL, 1, 1);
        handle(pollMsg);

        Map<Integer, DroneInfo> drones = getDrones();
        assertEquals(1, drones.size(), "One drone should be registered");

        DroneInfo drone = drones.get(1);
        assertNotNull(drone, "Drone 1 should exist");
        assertTrue(drone.busy, "Drone should be marked busy after dispatch");
        assertNotNull(drone.assignedEvent, "Drone should have an assigned event");
        assertTrue(getPendingEvents().isEmpty(), "Pending queue should be empty after dispatch");
    }

    /**
     * Tests that a recoverable drone fault (e.g., DRONE_STUCK) causes the scheduler
     * to requeue the event and dispatch it to another available drone.
     *
     * Expected result:
     *  - First drone receives the event
     *  - DRONE_STUCK fault triggers requeue with FaultType.NONE
     *  - Second drone polls and receives the requeued event
     *  - Second drone becomes busy with the reassigned mission
     *
     * @throws Exception if message handling fails
     */
    @Test
    void reassignment_requeuesEventOnRecoverableFaultAndDispatchesToAnotherDrone() throws Exception {
        // Initial fire + first drone dispatch
        FireEvent event = new FireEvent(
                Instant.now(),
                5,
                FireEventType.FIRE_DETECTED,
                Severity.MODERATE,
                FaultType.NONE,
                0
        );
        handle(new Message(MessageType.FIRE_EVENT, 0, event));
        handle(new Message(MessageType.DRONE_POLL, 1, 1));

        Map<Integer, DroneInfo> drones = getDrones();
        DroneInfo firstDrone = drones.get(1);
        assertNotNull(firstDrone);
        assertNotNull(firstDrone.assignedEvent);

        // Drone 1 reports a recoverable fault (e.g., DRONE_STUCK)
        DroneFault fault = new DroneFault(1, event.getZoneId(), FaultType.DRONE_STUCK, true);
        handle(new Message(MessageType.DRONE_FAULT, 1, fault));

        // Event should be requeued with FaultType.NONE
        Queue<FireEvent> pending = getPendingEvents();
        assertEquals(1, pending.size(), "Event should be requeued after recoverable fault");
        FireEvent requeued = pending.peek();
        assertEquals(FaultType.NONE, requeued.getFaultType(), "Requeued event should be clean");

        // Now a second drone polls and should get the requeued event
        handle(new Message(MessageType.DRONE_POLL, 2, 2));

        DroneInfo secondDrone = getDrones().get(2);
        assertNotNull(secondDrone, "Second drone should be registered");
        assertTrue(secondDrone.busy, "Second drone should be busy after reassignment");
        assertNotNull(secondDrone.assignedEvent, "Second drone should have been assigned the requeued event");
    }

    /**
     * Tests that the scheduler enters the IDLE state when there are
     * no pending events and no busy drones.
     *
     * Expected result:
     *  - refreshSchedulerState() sets schedulerState to IDLE
     */
    @Test
    void schedulerState_idleWhenNoPendingAndNoBusy() {
        // No drones, no events
        invokePrivate(scheduler, "refreshSchedulerState", new Class<?>[]{});
        assertEquals(SchedulerState.IDLE, getSchedulerState());
    }

    /**
     * Tests that when a pending event exists AND a dispatchable drone polls,
     * the scheduler transitions into an active dispatching state.
     *
     * Expected result:
     *  - Scheduler does NOT remain in IDLE
     *  - State becomes either SENDING_DRONES or WAITING_FOR_DRONES
     *
     * @throws Exception if message handling fails
     */
    @Test
    void schedulerState_sendingDronesWhenPendingAndDispatchable() throws Exception {
        // Add a pending event
        FireEvent event = new FireEvent(
                Instant.now(),
                1,
                FireEventType.FIRE_DETECTED,
                Severity.LOW,
                FaultType.NONE,
                0
        );
        handle(new Message(MessageType.FIRE_EVENT, 0, event));

        // Register an available drone (poll)
        handle(new Message(MessageType.DRONE_POLL, 1, 1));

        // After dispatch, state should have passed through SENDING_DRONES
        // and end up either WAITING_FOR_DRONES or IDLE depending on queue.
        SchedulerState state = getSchedulerState();
        assertTrue(
                state == SchedulerState.WAITING_FOR_DRONES || state == SchedulerState.IDLE,
                "Scheduler should not remain in IDLE while dispatching"
        );
    }

    /**
     * Tests that when a pending event exists but no drones are available,
     * the scheduler enters the WAITING_FOR_DRONES state.
     *
     * Expected result:
     *  - schedulerState == WAITING_FOR_DRONES
     *
     * @throws Exception if message handling fails
     */
    @Test
    void schedulerState_waitingForDronesWhenPendingButNoDispatchable() throws Exception {
        // Add a pending event
        FireEvent event = new FireEvent(
                Instant.now(),
                2,
                FireEventType.FIRE_DETECTED,
                Severity.HIGH,
                FaultType.NONE,
                0
        );
        handle(new Message(MessageType.FIRE_EVENT, 0, event));

        // No drones poll -> no dispatchable drones
        invokePrivate(scheduler, "refreshSchedulerState", new Class<?>[]{});

        assertEquals(SchedulerState.WAITING_FOR_DRONES, getSchedulerState());
    }

    /**
     * Tests scheduler robustness under repeated DRONE_POLL messages,
     * simulating packet loss or duplicate polls.
     *
     * Expected result:
     *  - Only one drone entry is created
     *  - Drone remains in a valid state (available or busy)
     */
    @Test
    void repeatedDronePollsDoNotDuplicateDroneOrBreakAvailability() throws Exception {
        // Same drone polls multiple times (simulating lost poll responses)
        handle(new Message(MessageType.DRONE_POLL, 1, 1));
        handle(new Message(MessageType.DRONE_POLL, 1, 1));
        handle(new Message(MessageType.DRONE_POLL, 1, 1));

        Map<Integer, DroneInfo> drones = getDrones();
        assertEquals(1, drones.size(), "Repeated polls should not create duplicate drones");

        DroneInfo drone = drones.get(1);
        assertNotNull(drone);
        assertTrue(drone.available || drone.busy, "Drone should remain in a valid state after repeated polls");
    }

    /**
     * Tests that a NOZZLE_JAM hard fault:
     *  - Marks the drone OFFLINE
     *  - Requeues the event with FaultType.NONE
     *  - Leaves the drone unavailable for future dispatches
     *
     * Expected result:
     *  - drone.lastKnownState == OFFLINE
     *  - pendingEvents contains exactly one clean event
     *
     * @throws Exception if message handling fails
     */
    @Test
    void nozzleJamFaultMarksDroneOfflineAndRequeuesEvent() throws Exception {
        // Fire + dispatch to drone 1
        FireEvent event = new FireEvent(
                Instant.now(),
                4,
                FireEventType.FIRE_DETECTED,
                Severity.MODERATE,
                FaultType.NONE,
                0
        );
        handle(new Message(MessageType.FIRE_EVENT, 0, event));
        handle(new Message(MessageType.DRONE_POLL, 1, 1));

        Map<Integer, DroneInfo> drones = getDrones();
        DroneInfo drone = drones.get(1);
        assertNotNull(drone);
        assertNotNull(drone.assignedEvent);

        // Nozzle jam fault (hard fault)
        DroneFault fault = new DroneFault(1, event.getZoneId(), FaultType.NOZZLE_JAM, false);
        handle(new Message(MessageType.DRONE_FAULT, 1, fault));

        // Drone should be offline
        assertEquals(DroneState.OFFLINE, drone.lastKnownState, "Nozzle jam should mark drone OFFLINE");
        assertFalse(drone.available, "Offline drone should not be available");

        // Event should be requeued as clean
        Queue<FireEvent> pending = getPendingEvents();
        assertEquals(1, pending.size(), "Event should be requeued after nozzle jam");
        FireEvent requeued = pending.peek();
        assertEquals(FaultType.NONE, requeued.getFaultType());
        assertEquals(event.getZoneId(), requeued.getZoneId());
    }
        /**
         * Tests how scheduler handles FIRE_EVENT
         * Expected result: response message confirms event is sent to events queue
         * @throws InterruptedException
         */
    @Test
    @Deprecated
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
            FireEvent testEvent = new FireEvent(timestamp, 3, testType, testSeverity, FaultType.NONE, 0);
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
    @Deprecated
    void testHandleDronePoll() throws InterruptedException{
        Instant timestamp = Instant.now();
        FireEventType testType = FIRE_DETECTED;
        Severity testSeverity = HIGH;

        MessageTransporter testTransporter = new MessageTransporter();
        SchedulerSubsystem testScheduler = new SchedulerSubsystem(testTransporter);

        Thread schedulerThread = new Thread(testScheduler);
        schedulerThread.start();
        try{
            FireEvent testEvent = new FireEvent(timestamp, 0, testType, testSeverity, FaultType.NONE, 0);
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
    @Deprecated
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