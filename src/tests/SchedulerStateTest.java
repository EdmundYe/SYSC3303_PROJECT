package tests;

import common.SchedulerEvent;
import common.SchedulerState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SchedulerStateTest {

    @Test
    void testIdleToWaitingForDone() {
        SchedulerState s = SchedulerState.IDLE;
        SchedulerState next = s.next(SchedulerEvent.DISPATCH_SENT);
        assertEquals(SchedulerState.WAITING_FOR_DONE, next);
    }

    @Test
    void testWaitingForDoneToIdle() {
        SchedulerState s = SchedulerState.WAITING_FOR_DONE;
        SchedulerState next = s.next(SchedulerEvent.DRONE_DONE_RECEIVED);
        assertEquals(SchedulerState.IDLE, next);
    }

    @Test
    void testIrrelevantEventKeepsState() {
        SchedulerState s = SchedulerState.IDLE;
        SchedulerState next = s.next(SchedulerEvent.DRONE_DONE_RECEIVED); // irrelevant to IDLE
        assertEquals(SchedulerState.IDLE, next);
    }
}