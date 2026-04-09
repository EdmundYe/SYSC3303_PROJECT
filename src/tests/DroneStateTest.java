package tests;

import common.DroneEvent;
import common.DroneState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the DroneState finite‑state machine.
 */
class DroneStateTest {

    /**
     * Ensures that an IDLE drone transitions to EN_ROUTE when it receives
     * a TASK_RECEIVED event.
     */
    @Test
    void testIdleToEnRoute() {
        DroneState s = DroneState.IDLE;
        DroneState next = s.next(DroneEvent.TASK_RECEIVED);
        assertEquals(DroneState.EN_ROUTE, next);
    }

    /**
     * Ensures that an EN_ROUTE drone transitions to DROPPING when it receives
     * an ARRIVED event.
     */
    @Test
    void testEnRouteToDropping() {
        DroneState s = DroneState.EN_ROUTE;
        DroneState next = s.next(DroneEvent.ARRIVED);
        assertEquals(DroneState.DROPPING, next);
    }

    /**
     * Ensures that a DROPPING drone transitions to RETURNING when it receives
     * a DROP_COMPLETE event.
     */
    @Test
    void testDroppingToReturning() {
        DroneState s = DroneState.DROPPING;
        DroneState next = s.next(DroneEvent.DROP_COMPLETE);
        assertEquals(DroneState.RETURNING, next);
    }

    /**
     * Ensures that a RETURNING drone transitions to DONE when it receives
     * a RETURN_COMPLETE event.
     */
    @Test
    void testReturningToDone() {
        DroneState s = DroneState.RETURNING;
        DroneState next = s.next(DroneEvent.RETURN_COMPLETE);
        assertEquals(DroneState.DONE, next);
    }

    /**
     * Verifies that a DONE drone transitions back to IDLE when it receives
     * a TASK_RECEIVED event. According to the state machine, DONE always
     * returns to IDLE regardless of the event.
     */
    @Test
    void testDoneCyclesToIdle() {
        DroneState s = DroneState.DONE;
        DroneState next = s.next(DroneEvent.TASK_RECEIVED);
        // DONE.next returns IDLE regardless of event (according to provided enum)
        assertEquals(DroneState.IDLE, next);
    }

    /**
     * Ensures that unrelated events do not change the drone's state.
     * For example, an IDLE drone receiving an ARRIVED event should remain IDLE.
     */
    @Test
    void testUnrelatedEventsKeepState() {
        DroneState s = DroneState.IDLE;
        DroneState next = s.next(DroneEvent.ARRIVED); // irrelevant event for IDLE
        assertEquals(DroneState.IDLE, next);
    }
}