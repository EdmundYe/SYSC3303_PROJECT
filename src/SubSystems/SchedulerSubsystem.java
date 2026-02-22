package SubSystems;

import MessageTransport.MessageTransporter;
import MessageTransport.SendAddress;
import common.*;

import java.util.ArrayDeque;
import java.util.Queue;

public class SchedulerSubsystem implements Runnable {

    private final MessageTransporter transport;
    private final Queue<FireEvent> pendingEvents = new ArrayDeque<>();

    private final int SINGLE_DRONE_ID = 1;

    private FireEvent activeEvent = null;

    //for gui
    private SystemCounts counts = null;

    // Scheduler state machine
    private SchedulerState schedulerState = SchedulerState.IDLE;

    public SchedulerSubsystem(MessageTransporter transport) {
        this.transport = transport;
    }

    public SchedulerSubsystem(MessageTransporter transport, SystemCounts counts) {
        this.transport = transport;
        this.counts = counts;
    }

    @Override
    public void run() {
        System.out.println("[SCHEDULER] Started");
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Message msg = transport.receive(SendAddress.SCHEDULER);
                handle(msg);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.out.println("[SCHEDULER] Stopped");
    }

    void handle(Message msg) {
        switch (msg.getType()) {

            case FIRE_EVENT -> {
                FireEvent event = (FireEvent) msg.getPayload();
                pendingEvents.add(event);
                System.out.println("[SCHEDULER] Received FIRE_EVENT: " + event);

                transition(SchedulerEvent.FIRE_RECEIVED);
                if (counts != null) { counts.incActiveFires(); }

                tryDispatchIfPossible();
            }

            case DRONE_POLL -> {
                int droneId = (int) msg.getPayload();
                System.out.println("[SCHEDULER] Drone polled: Drone " + droneId);

                transition(SchedulerEvent.DRONE_POLL);
                tryDispatchIfPossible();
            }

            case DRONE_DONE -> {
                DroneStatus status = (DroneStatus) msg.getPayload();
                System.out.println("[SCHEDULER] Drone completed task: " + status);

                transition(SchedulerEvent.DRONE_DONE_RECEIVED);

                // Mission finished; clear active job and attempt next
                activeEvent = null;

                //update GUI
                if (counts != null) { counts.decActiveFires(); }

                tryDispatchIfPossible();
            }

            default -> {
                // ignore other message types for now
            }
        }
    }

    private void tryDispatchIfPossible() {
        // Only dispatch when scheduler is idle and there is no active job.
        if (schedulerState != SchedulerState.IDLE) return;
        if (activeEvent != null) return;
        if (pendingEvents.isEmpty()) return;

        activeEvent = pendingEvents.poll();

        DroneCommand cmd = new DroneCommand(
                "REQ-" + activeEvent.getTimestamp().toEpochMilli(),
                DroneCommandOptions.DISPATCH,
                activeEvent.getZoneId(),
                activeEvent.getSeverity()
        );

        transport.send(SendAddress.DRONE, Message.droneTask(SINGLE_DRONE_ID, cmd));
        transition(SchedulerEvent.DISPATCH_SENT);

        System.out.println("[SCHEDULER] Dispatched Drone " + SINGLE_DRONE_ID
                + " to zone " + activeEvent.getZoneId());
    }

    private void transition(SchedulerEvent event) {
        SchedulerState before = schedulerState;
        schedulerState = schedulerState.next(event);

        if (before != schedulerState) {
            System.out.println("[SCHEDULER] State: " + before + " -> " + schedulerState + " on " + event);
        }
    }
}
