package tests;

import common.DroneEvent;
import common.DroneState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DroneStateTest {

    @Test
    void testIdleToEnRoute() {
        DroneState s = DroneState.IDLE;
        DroneState next = s.next(DroneEvent.TASK_RECEIVED);
        assertEquals(DroneState.EN_ROUTE, next);
    }

    @Test
    void testEnRouteToDropping() {
        DroneState s = DroneState.EN_ROUTE;
        DroneState next = s.next(DroneEvent.ARRIVED);
        assertEquals(DroneState.DROPPING, next);
    }

    @Test
    void testDroppingToReturning() {
        DroneState s = DroneState.DROPPING;
        DroneState next = s.next(DroneEvent.DROP_COMPLETE);
        assertEquals(DroneState.RETURNING, next);
    }

    @Test
    void testReturningToDone() {
        DroneState s = DroneState.RETURNING;
        DroneState next = s.next(DroneEvent.RETURN_COMPLETE);
        assertEquals(DroneState.DONE, next);
    }

    @Test
    void testDoneCyclesToIdle() {
        DroneState s = DroneState.DONE;
        DroneState next = s.next(DroneEvent.TASK_RECEIVED);
        // DONE.next returns IDLE regardless of event (according to provided enum)
        assertEquals(DroneState.IDLE, next);
    }

    @Test
    void testUnrelatedEventsKeepState() {
        DroneState s = DroneState.IDLE;
        DroneState next = s.next(DroneEvent.ARRIVED); // irrelevant event for IDLE
        assertEquals(DroneState.IDLE, next);
    }
}