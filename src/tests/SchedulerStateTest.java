package tests;

import common.SchedulerEvent;
import common.SchedulerState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SchedulerStateTest {

    @Test
    void idle_onFireReceived_goesToSendingDrones() {
        assertEquals(
                SchedulerState.SENDING_DRONES,
                SchedulerState.IDLE.next(SchedulerEvent.FIRE_RECEIVED)
        );
    }

    @Test
    void idle_onOtherEvents_staysIdle() {
        assertEquals(SchedulerState.IDLE, SchedulerState.IDLE.next(SchedulerEvent.DRONE_POLL));
        assertEquals(SchedulerState.IDLE, SchedulerState.IDLE.next(SchedulerEvent.DISPATCH_SENT));
        assertEquals(SchedulerState.IDLE, SchedulerState.IDLE.next(SchedulerEvent.DRONE_STATUS_RECEIVED));
        assertEquals(SchedulerState.IDLE, SchedulerState.IDLE.next(SchedulerEvent.DRONE_DONE_RECEIVED));
    }

    @Test
    void sendingDrones_onDispatchSent_goesToWaitingForDrones() {
        assertEquals(
                SchedulerState.WAITING_FOR_DRONES,
                SchedulerState.SENDING_DRONES.next(SchedulerEvent.DISPATCH_SENT)
        );
    }

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

    @Test
    void waitingForDrones_onDroneDone_goesToSendingDrones() {
        assertEquals(
                SchedulerState.SENDING_DRONES,
                SchedulerState.WAITING_FOR_DRONES.next(SchedulerEvent.DRONE_DONE_RECEIVED)
        );
    }

    @Test
    void waitingForDrones_onDronePoll_goesToSendingDrones() {
        assertEquals(
                SchedulerState.SENDING_DRONES,
                SchedulerState.WAITING_FOR_DRONES.next(SchedulerEvent.DRONE_POLL)
        );
    }

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

    @Test
    void testIrrelevantEventKeepsState() {
        SchedulerState s = SchedulerState.IDLE;
        SchedulerState next = s.next(SchedulerEvent.DRONE_DONE_RECEIVED); // irrelevant to IDLE
        assertEquals(SchedulerState.IDLE, next);
    }

    @Deprecated
    void testIdleToWaitingForDone() {
        SchedulerState s = SchedulerState.IDLE;
        SchedulerState next = s.next(SchedulerEvent.DISPATCH_SENT);
        assertEquals(SchedulerState.WAITING_FOR_DRONES, next);
    }

    @Deprecated
    void testWaitingForDoneToIdle() {
        SchedulerState s = SchedulerState.WAITING_FOR_DRONES;
        SchedulerState next = s.next(SchedulerEvent.DRONE_DONE_RECEIVED);
        assertEquals(SchedulerState.IDLE, next);
    }


}