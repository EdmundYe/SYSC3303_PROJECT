package tests;

import common.SchedulerEvent;
import common.SchedulerState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SchedulerStateTest {

    @Test
    @Deprecated
    void testIdleToWaitingForDone() {
        SchedulerState s = SchedulerState.IDLE;
        SchedulerState next = s.next(SchedulerEvent.DISPATCH_SENT);
        assertEquals(SchedulerState.WAITING_FOR_DRONES, next);
    }

    @Test
    @Deprecated
    void testWaitingForDoneToIdle() {
        SchedulerState s = SchedulerState.WAITING_FOR_DRONES;
        SchedulerState next = s.next(SchedulerEvent.DRONE_DONE_RECEIVED);
        assertEquals(SchedulerState.IDLE, next);
    }

    @Test
    @Deprecated
    void testIrrelevantEventKeepsState() {
        SchedulerState s = SchedulerState.IDLE;
        SchedulerState next = s.next(SchedulerEvent.DRONE_DONE_RECEIVED); // irrelevant to IDLE
        assertEquals(SchedulerState.IDLE, next);
    }
}