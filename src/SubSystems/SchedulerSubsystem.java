package SubSystems;

import MessageTransport.MessageTransporter;

import common.*;
import MessageTransport.SendAddress;

import java.util.ArrayDeque;
import java.util.Queue;

public class SchedulerSubsystem implements Runnable {

    private final MessageTransporter transport;
    private final Queue<FireEvent> pendingEvents = new ArrayDeque<>();

    public SchedulerSubsystem(MessageTransporter transport) {
        this.transport = transport;
    }

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

    //based off the message type it will generate the specified message
    private void handle(Message msg) {
        switch (msg.getType()) {

            case FIRE_EVENT -> {
                FireEvent event = (FireEvent) msg.getPayload();
                pendingEvents.add(event);
                System.out.println("[SCHEDULER] Received FIRE_EVENT: " + event);
            }

            case DRONE_POLL -> {
                int droneId = (int) msg.getPayload();
                System.out.println("[SCHEDULER] Drone polled: Drone " + droneId);

                // Only dispatch if there is a pending fire event
                if (!pendingEvents.isEmpty()) {
                    FireEvent event = pendingEvents.poll();

                    DroneCommand cmd = new DroneCommand(
                            "REQ-" + event.getTimestamp().toEpochMilli(),
                            DroneCommandOptions.DISPATCH,
                            event.getZoneId(),
                            event.getSeverity()
                    );

                    transport.send(
                            SendAddress.DRONE,
                            Message.droneTask(droneId, cmd)
                    );

                    System.out.println("[SCHEDULER] Dispatched task to Drone " + droneId +
                            " for zone " + event.getZoneId());
                }
            }

            case DRONE_DONE -> {
                DroneStatus status = (DroneStatus) msg.getPayload();
                System.out.println("[SCHEDULER] Drone completed task: " + status);
            }
        }
    }
}
