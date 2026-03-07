package SubSystems;

import MessageTransport.MessageTransporter;
import MessageTransport.SendAddress;
import common.*;

import java.util.ArrayDeque;
import java.util.Queue;
import java.net.*;
import java.io.*;

public class SchedulerSubsystem {

    private final MessageTransporter transport;
    private final Queue<FireEvent> pendingEvents = new ArrayDeque<>();

    DatagramPacket receivePacket;
    DatagramSocket receiveSocket;

    private final int SINGLE_DRONE_ID = 1;

    private FireEvent activeEvent = null;

    //for gui
    private SystemCounts counts;

    // Scheduler state machine
    private SchedulerState schedulerState = SchedulerState.IDLE;

//    public SchedulerSubsystem(MessageTransporter transport) {
//        this.transport = transport;
//    }

    public SchedulerSubsystem(MessageTransporter transport, SystemCounts counts) {
        try{
            receiveSocket = new DatagramSocket(6000);
        } catch (SocketException e) {
            e.printStackTrace();
        }
        this.transport = transport;
        this.counts = counts;
    }

//    @Override
//    public void run() {
//        System.out.println("[SCHEDULER] Started");
//        try {
//            while (!Thread.currentThread().isInterrupted()) {
//                Message msg = transport.receive(SendAddress.SCHEDULER);
//                handle(msg);
//            }
//        } catch (InterruptedException e) {
//            Thread.currentThread().interrupt();
//        }
//        System.out.println("[SCHEDULER] Stopped");
//    }

    /**
     * Scheduler will listen from incident port and send commands to drone port
     * messages works through messages type. First convert to byte then convert back
     */
    void receiveAndSend(){
        while(true){
            byte[] message = new byte[100];
            receivePacket = new DatagramPacket(message, message.length);
            try{
                receiveSocket.receive(receivePacket);
            } catch (IOException e) {
                e.printStackTrace();
            }
            byte[] data = new byte[receivePacket.getLength()];
            System.arraycopy(receivePacket.getData(), receivePacket.getOffset(), data, 0, receivePacket.getLength());
            InetAddress incomingAdr = receivePacket.getAddress();
            int imcomingPort = receivePacket.getPort();
            handle(Message.fromBytes(data), incomingAdr, imcomingPort);
        }
    }

    void handle(Message msg, InetAddress adr, int port) {
        switch (msg.getType()) {

            case FIRE_EVENT -> {
                FireEvent event = (FireEvent) msg.getPayload();
                pendingEvents.add(event);
                System.out.println("[SCHEDULER] Received FIRE_EVENT: " + event);

                transition(SchedulerEvent.FIRE_RECEIVED);
                if (counts != null) { counts.incActiveFires(); }

                tryDispatchIfPossible(adr, port);
            }

            case DRONE_POLL -> {
                int droneId = (int) msg.getPayload();
                System.out.println("[SCHEDULER] Drone polled: Drone " + droneId);

                transition(SchedulerEvent.DRONE_POLL);
                tryDispatchIfPossible(adr, port);
            }

            case DRONE_DONE -> {
                DroneStatus status = (DroneStatus) msg.getPayload();
                System.out.println("[SCHEDULER] Drone completed task: " + status);

                transition(SchedulerEvent.DRONE_DONE_RECEIVED);

                // Mission finished; clear active job and attempt next
                activeEvent = null;

                //update GUI
                if (counts != null) { counts.decActiveFires(); }

                tryDispatchIfPossible(adr, port);
            }

            default -> {
                // ignore other message types for now
            }
        }
    }

    private void tryDispatchIfPossible(InetAddress adr, int port) {
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

        byte[] msg = Message.droneTask(SINGLE_DRONE_ID, cmd).toBytes();
        // please change to port for drones subsystem
        DatagramPacket msgPacket = new DatagramPacket(msg, msg.length, adr, port);

        try{
            receiveSocket.send(msgPacket);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // transport.send(SendAddress.DRONE, Message.droneTask(SINGLE_DRONE_ID, cmd));
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

    public static void main(String args[]){
            MessageTransporter transport = new MessageTransporter();
            SystemCounts counts = null;
            SchedulerSubsystem scheduler = new SchedulerSubsystem(transport, counts);
            scheduler.receiveAndSend();
    }
}