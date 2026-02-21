package SubSystems;

import MessageTransport.MessageTransporter;
import MessageTransport.SendAddress;
import common.*;

public class DroneSubsystem implements Runnable {
    // Unique identifier for this drone
    private final int droneId;

    private DroneState state = DroneState.IDLE;

    // Transport mechanism used to communicate with the Scheduler
    private final MessageTransporter transport;

    // Indicates whether the drone should attempt to receive a task
    private boolean waitingForTask = false;

    //for GUI
    private SystemCounts counts = null;

    /**
     * Constructs a SubSystems.DroneSubsystem.
     *
     * @param droneId   unique ID of the drone
     * @param transport shared MessageTransporter instance
     */
    public DroneSubsystem(int droneId, MessageTransporter transport) {
        this.droneId = droneId;
        this.transport = transport;
    }

    public DroneSubsystem(int droneId, MessageTransporter transport, SystemCounts counts) {
        this.droneId = droneId;
        this.transport = transport;
        this.counts = counts;
    }

    /**
     * Main execution loop of the Drone Subsystem.
     * The drone continuously polls the Scheduler for tasks,
     * executes received commands, and reports completion.
     */
    @Override
    public void run() {
        System.out.println("[DRONE " + droneId + "] Drone subsystem started");

        try {
            while (!Thread.currentThread().isInterrupted()) {

                // Poll scheduler
                transport.send(
                        SendAddress.SCHEDULER,
                        Message.dronePoll(droneId)
                );
                System.out.println("[DRONE " + droneId + "] Polling scheduler for tasks");

                // Try to receive ONLY if scheduler has sent something
                if (waitingForTask) {
                    Message msg = transport.receive(SendAddress.DRONE);
                    handleMessage(msg);
                    waitingForTask = false;
                }

                Thread.sleep(500);
                waitingForTask = true; // allow receive on next loop
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println("[DRONE " + droneId + "] Drone subsystem stopped");
    }

    /**
     * Handles messages received from the Scheduler.
     *
     * @param msg message sent by the Scheduler
     */
    private void handleMessage(Message msg) throws InterruptedException {

        switch (msg.getType()) {

            case DRONE_TASK -> {
                System.out.println("[DRONE " + droneId + "] Received task");

                if (counts != null) { counts.incBusyDrones(); }


                DroneCommand command = (DroneCommand) msg.getPayload();

                // Simulate execution of the drone task
                executeCommand(command);

                // After completing the task, report status back to Scheduler
                DroneStatus status = new DroneStatus(
                        droneId,
                        state,
                        command.get_zone_id(),
                        null
                );

                Message doneMsg = Message.droneDone(droneId, status);
                transport.send(SendAddress.SCHEDULER, doneMsg);

                System.out.println("[DRONE " + droneId + "] Task completed and reported");

                if (counts != null) { counts.decBusyDrones(); }
            }

            default -> {
            }
        }
    }

    /**
     * Simulates execution of a drone command.
     *
     * For now this method only uses Thread.sleep()
     * to represent flight, agent drop, and return.
     *
     * @param command the command received from the Scheduler
     */
    private void executeCommand(DroneCommand command) throws InterruptedException {

        transition(DroneEvent.TASK_RECEIVED);
        System.out.println("[DRONE " + droneId + "] Dispatching to zone "
                + command.get_zone_id());

        Thread.sleep(2000); // simulate travel time

        transition(DroneEvent.ARRIVED);

        System.out.println("[DRONE " + droneId + "] En route");

        Thread.sleep(2000); // simulate agent drop
        System.out.println("[DRONE " + droneId + "] Dropping agent ("
                + command.getSeverity() + ")");

        transition(DroneEvent.DROP_COMPLETE);

        Thread.sleep(2000); // simulate return to base
        System.out.println("[DRONE " + droneId + "] Returning to base");

        transition(DroneEvent.RETURN_COMPLETE);
    }

    private void transition(DroneEvent event) {
        state = state.next(event);
    }
}