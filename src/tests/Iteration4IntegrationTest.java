package tests;

import MessageTransport.MessageTransporter;
import SubSystems.DroneSubsystem;
import SubSystems.FireIncidentSubsystem;
import SubSystems.SchedulerSubsystem;
import common.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full integration tests for Iteration 4.
 * Covers:
 *  - FireIncidentSubsystem sends FIRE_EVENT messages correctly
 *  - SchedulerSubsystem receives events, dispatches drones, and sends FIRE_OUT
 *  - DroneSubsystem executes missions and reports DONE
 *  - Faults injected via CSV propagate through the entire system
 *
 * Tests use temporary CSV files to simulate real input.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Iteration4IntegrationTest {
        private Thread fireThread;
        private Thread schedulerThread;
        private Thread droneThread;

        private FireIncidentSubsystem fire;
        private SchedulerSubsystem scheduler;
        private DroneSubsystem drone;

        private Path tempCsv;

        @BeforeEach
        void setUp() throws Exception {
            tempCsv = Files.createTempFile("firetest", ".csv");
        }

        private void closeSocket(Object subsystem, String fieldName) {
            try {
                Field f = subsystem.getClass().getDeclaredField(fieldName);
                f.setAccessible(true);
                DatagramSocket s = (DatagramSocket) f.get(subsystem);
                if (s != null && !s.isClosed()) {
                    s.close();
                }
            } catch (Exception ignored) {}
        }

        @AfterEach
        void tearDown() throws Exception {
            if (fireThread != null) fireThread.interrupt();
            if (schedulerThread != null) schedulerThread.interrupt();
            if (droneThread != null) droneThread.interrupt();

            // Give threads a short moment to stop and sockets to be released
            Thread.sleep(300);

            if (fire != null) closeSocket(fire, "socket");
            if (scheduler != null) {
                closeSocket(scheduler, "receiveSocket");
                closeSocket(scheduler, "sendSocket");
            }
            if (drone != null) closeSocket(drone, "socket");
        }

        /**
         * Polls the FireIncidentSubsystem's outstandingFires field until it matches
         * the expected value or the timeout elapses.
         *
         * @param expected expected outstanding fires
         * @param timeout  maximum wait duration
         * @return true if expected reached, false if timed out
         * @throws Exception reflection errors
         */
        private boolean waitForOutstanding(int expected, Duration timeout) throws Exception {
            Field outstanding = FireIncidentSubsystem.class.getDeclaredField("outstandingFires");
            outstanding.setAccessible(true);

            long deadline = System.currentTimeMillis() + timeout.toMillis();
            while (System.currentTimeMillis() < deadline) {
                int current = (int) outstanding.get(fire);
                if (current == expected) return true;
                Thread.sleep(500);
            }
            return false;
        }

        /**
         * Tests a complete end‑to‑end mission flow for a single LOW‑severity fire.
         *
         * Expected result:
         *  - FireIncidentSubsystem sends FIRE_EVENT
         *  - Scheduler dispatches the drone
         *  - Drone completes the mission and reports DONE
         *  - Scheduler sends FIRE_OUT
         *  - FireIncidentSubsystem reduces outstandingFires to 0 within timeout
         *
         * This verifies the core functional behaviour of the entire system.
         */
        @Test
        @Order(1)
        void fullMissionFlow_singleEvent() throws Exception {

            // Create CSV with one simple event (LOW severity to keep runtime reasonable)
            Files.writeString(tempCsv,
                    "Time,ZoneId,EventType,Severity,FaultType,FaultDelaySeconds\n" +
                            "14:03:15,3,FIRE_DETECTED,LOW,NONE,0\n");

            fire = new FireIncidentSubsystem(tempCsv.toString());
            scheduler = new SchedulerSubsystem(new MessageTransporter(), null);

            // Use a unique drone id to avoid port collisions with other tests
            drone = new DroneSubsystem(2);

            fireThread = new Thread(fire);
            schedulerThread = new Thread(scheduler);
            droneThread = new Thread(drone);

            fireThread.start();
            schedulerThread.start();
            droneThread.start();

            // Wait up to 90 seconds for the outstanding counter to reach 0
            boolean completed = waitForOutstanding(0, Duration.ofSeconds(90));

            // If not completed, capture the current outstanding value for assertion message
            Field outstanding = FireIncidentSubsystem.class.getDeclaredField("outstandingFires");
            outstanding.setAccessible(true);
            int remaining = (int) outstanding.get(fire);

            assertTrue(completed, "All fires should be resolved end‑to‑end within timeout (remaining=" + remaining + ")");
        }

        /**
         * Tests that multiple fire events across different zones are all processed
         * and extinguished successfully by the system.
         *
         * Expected result:
         *  - All events are dispatched in order
         *  - Drone completes each mission sequentially
         *  - FireIncidentSubsystem reports outstandingFires = 0 within timeout
         *
         * This validates multi‑zone scheduling and queue draining behaviour.
         */
        @Test
        @Order(2)
        void multiZoneDispatch_allEventsProcessed() throws Exception {

            Files.writeString(tempCsv,
                    "Time,ZoneId,EventType,Severity,FaultType,FaultDelaySeconds\n" +
                            "14:03:15,3,FIRE_DETECTED,LOW,NONE,0\n" +
                            "14:03:15,2,FIRE_DETECTED,LOW,NONE,0\n" +
                            "14:03:20,1,FIRE_DETECTED,LOW,NONE,0\n");

            fire = new FireIncidentSubsystem(tempCsv.toString());
            scheduler = new SchedulerSubsystem(new MessageTransporter(), null);

            // Use a unique drone id for this test
            drone = new DroneSubsystem(3);

            fireThread = new Thread(fire);
            schedulerThread = new Thread(scheduler);
            droneThread = new Thread(drone);

            fireThread.start();
            schedulerThread.start();
            droneThread.start();

            // Wait up to 120 seconds for all events to be processed
            boolean completed = waitForOutstanding(0, Duration.ofSeconds(120));

            Field outstanding = FireIncidentSubsystem.class.getDeclaredField("outstandingFires");
            outstanding.setAccessible(true);
            int remaining = (int) outstanding.get(fire);

            assertTrue(completed, "All multi‑zone events must be extinguished within timeout (remaining=" + remaining + ")");
        }

        /**
         * Tests the full recoverable‑fault flow for DRONE_STUCK.
         *
         * Expected result:
         *  - Drone reports DRONE_STUCK after the configured delay
         *  - Scheduler requeues the event with FaultType.NONE
         *  - Drone recovers and completes the mission
         *  - FireIncidentSubsystem eventually receives FIRE_OUT
         *
         * This verifies correct handling of recoverable mid‑mission faults.
         */
        @Test
        @Order(3)
        void faultInjection_droneStuck_recoveryFlow() throws Exception {

            Files.writeString(tempCsv,
                    "Time,ZoneId,EventType,Severity,FaultType,FaultDelaySeconds\n" +
                            "14:03:20,1,FIRE_DETECTED,LOW,DRONE_STUCK,2\n");

            fire = new FireIncidentSubsystem(tempCsv.toString());
            scheduler = new SchedulerSubsystem(new MessageTransporter(), null);

            // Use a unique drone id for this test
            drone = new DroneSubsystem(4);

            fireThread = new Thread(fire);
            schedulerThread = new Thread(scheduler);
            droneThread = new Thread(drone);

            fireThread.start();
            schedulerThread.start();
            droneThread.start();

            // Wait up to 120 seconds for the recoverable fault to be handled and event completed
            boolean completed = waitForOutstanding(0, Duration.ofSeconds(120));

            Field outstanding = FireIncidentSubsystem.class.getDeclaredField("outstandingFires");
            outstanding.setAccessible(true);
            int remaining = (int) outstanding.get(fire);

            assertTrue(completed, "Recoverable DRONE_STUCK fault should still result in FIRE_OUT within timeout (remaining=" + remaining + ")");
        }

        /**
         * Tests the hard‑fault behaviour for NOZZLE_JAM.
         *
         * Expected result:
         *  - Drone reports NOZZLE_JAM
         *  - Scheduler marks the drone OFFLINE
         *  - Event is requeued but cannot be serviced (only one drone exists)
         *  - outstandingFires remains > 0
         *
         * This verifies correct handling of non‑recoverable faults.
         */
        @Test
        @Order(4)
        void faultInjection_nozzleJam_droneOffline() throws Exception {

            Files.writeString(tempCsv,
                    "Time,ZoneId,EventType,Severity,FaultType,FaultDelaySeconds\n" +
                            "14:03:25,4,FIRE_DETECTED,LOW,NOZZLE_JAM,1\n");

            fire = new FireIncidentSubsystem(tempCsv.toString());
            scheduler = new SchedulerSubsystem(new MessageTransporter(), null);
            // Keep drone id 1 for this test as requested (do not alter)
            drone = new DroneSubsystem(1);

            fireThread = new Thread(fire);
            schedulerThread = new Thread(scheduler);
            droneThread = new Thread(drone);

            fireThread.start();
            schedulerThread.start();
            droneThread.start();

            // This test intentionally expects the fire to remain outstanding because the only drone goes offline.
            // Wait a short while to allow the fault to propagate, then assert outstanding > 0.
            Thread.sleep(30000);

            Field outstanding = FireIncidentSubsystem.class.getDeclaredField("outstandingFires");
            outstanding.setAccessible(true);
            int remaining = (int) outstanding.get(fire);

            assertTrue(remaining > 0,
                    "NOZZLE_JAM should leave the system unable to extinguish the fire (drone offline)");
        }
}
