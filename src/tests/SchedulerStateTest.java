package tests;

import common.SchedulerEvent;
import common.SchedulerState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the SchedulerState finite‑state machine.
 */
class SchedulerStateTest {

    /**
     * Ensures that when the scheduler is IDLE and receives FIRE_RECEIVED,
     * it transitions to SENDING_DRONES.
     */
    @Test
    void idle_onFireReceived_goesToSendingDrones() {
        assertEquals(
                SchedulerState.SENDING_DRONES,
                SchedulerState.IDLE.next(SchedulerEvent.FIRE_RECEIVED)
        );
    }

    /**
     * Verifies that IDLE ignores all events except FIRE_RECEIVED and
     * therefore remains in the IDLE state.
     */
    @Test
    void idle_onOtherEvents_staysIdle() {
        assertEquals(SchedulerState.IDLE, SchedulerState.IDLE.next(SchedulerEvent.DRONE_POLL));
        assertEquals(SchedulerState.IDLE, SchedulerState.IDLE.next(SchedulerEvent.DISPATCH_SENT));
        assertEquals(SchedulerState.IDLE, SchedulerState.IDLE.next(SchedulerEvent.DRONE_STATUS_RECEIVED));
        assertEquals(SchedulerState.IDLE, SchedulerState.IDLE.next(SchedulerEvent.DRONE_DONE_RECEIVED));
    }

    /**
     * Ensures that SENDING_DRONES transitions to WAITING_FOR_DRONES
     * when a DISPATCH_SENT event occurs.
     */
    @Test
    void sendingDrones_onDispatchSent_goesToWaitingForDrones() {
        assertEquals(
                SchedulerState.WAITING_FOR_DRONES,
                SchedulerState.SENDING_DRONES.next(SchedulerEvent.DISPATCH_SENT)
        );
    }

    /**
     * Verifies that SENDING_DRONES ignores all events except DISPATCH_SENT
     * and therefore remains in SENDING_DRONES.
     */
    @Test
    void sendingDrones_onOtherEvents_staysSendingDrones() {
        assertEquals(SchedulerState.SENDING_DRONES,
                SchedulerState.SENDING_DRONES.next(SchedulerEvent.FIRE_RECEIVED));
        assertEquals(SchedulerState.SENDING_DRONES,
                SchedulerState.SENDING_DRONES.next(SchedulerEvent.DRONE_POLL));
        assertEquals(SchedulerState.SENDING_DRONES,
                SchedulerState.SENDING_DRONES.next(SchedulerEvent.DRONE_STATUS_RECEIVED));
        assertEquals(SchedulerState.SENDING_DRONES,
                SchedulerState.SENDING_DRONES.next(SchedulerEvent.DRONE_DONE_RECEIVED));
    }

    /**
     * Ensures that WAITING_FOR_DRONES transitions back to SENDING_DRONES
     * when a drone completes a mission (DRONE_DONE_RECEIVED).
     */
    @Test
    void waitingForDrones_onDroneDone_goesToSendingDrones() {
        assertEquals(
                SchedulerState.SENDING_DRONES,
                SchedulerState.WAITING_FOR_DRONES.next(SchedulerEvent.DRONE_DONE_RECEIVED)
        );
    }

    /**
     * Ensures that WAITING_FOR_DRONES transitions to SENDING_DRONES
     * when a new drone becomes available (DRONE_POLL).
     */
    @Test
    void waitingForDrones_onDronePoll_goesToSendingDrones() {
        assertEquals(
                SchedulerState.SENDING_DRONES,
                SchedulerState.WAITING_FOR_DRONES.next(SchedulerEvent.DRONE_POLL)
        );
    }

    /**
     * Verifies that WAITING_FOR_DRONES ignores status updates, new fire events,
     * and DISPATCH_SENT, remaining in WAITING_FOR_DRONES.
     */
    @Test
    void waitingForDrones_onStatusOrFireReceived_staysWaiting() {
        assertEquals(
                SchedulerState.WAITING_FOR_DRONES,
                SchedulerState.WAITING_FOR_DRONES.next(SchedulerEvent.DRONE_STATUS_RECEIVED)
        );
        assertEquals(
                SchedulerState.WAITING_FOR_DRONES,
                SchedulerState.WAITING_FOR_DRONES.next(SchedulerEvent.FIRE_RECEIVED)
        );
        assertEquals(
                SchedulerState.WAITING_FOR_DRONES,
                SchedulerState.WAITING_FOR_DRONES.next(SchedulerEvent.DISPATCH_SENT)
        );
    }

    /**
     * Ensures that irrelevant events do not cause a state transition.
     * For example, IDLE receiving DRONE_DONE_RECEIVED should remain IDLE.
     */
    @Test
    void testIrrelevantEventKeepsState() {
        SchedulerState s = SchedulerState.IDLE;
        SchedulerState next = s.next(SchedulerEvent.DRONE_DONE_RECEIVED); // irrelevant to IDLE
        assertEquals(SchedulerState.IDLE, next);
    }

    /**
     * Legacy behavior test: previously IDLE transitioned to WAITING_FOR_DRONES
     * on DISPATCH_SENT. Marked deprecated because the state machine has changed.
     */
    @Deprecated
    void testIdleToWaitingForDone() {
        SchedulerState s = SchedulerState.IDLE;
        SchedulerState next = s.next(SchedulerEvent.DISPATCH_SENT);
        assertEquals(SchedulerState.WAITING_FOR_DRONES, next);
    }

    /**
     * Legacy behavior test: previously WAITING_FOR_DRONES transitioned to IDLE
     * on DRONE_DONE_RECEIVED. Marked deprecated because the state machine has changed.
     */
    @Deprecated
    void testWaitingForDoneToIdle() {
        SchedulerState s = SchedulerState.WAITING_FOR_DRONES;
        SchedulerState next = s.next(SchedulerEvent.DRONE_DONE_RECEIVED);
        assertEquals(SchedulerState.IDLE, next);
    }


}