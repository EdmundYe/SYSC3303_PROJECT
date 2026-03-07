package SubSystems;

import MessageTransport.MessageTransporter;
import MessageTransport.SendAddress;
import common.*;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.net.*;
import java.io.*;

public class SchedulerSubsystem {

    private final MessageTransporter transport;
    private final Map<Integer, DroneInfo> drones = new HashMap<>();
    private final Queue<FireEvent> pendingEvents = new ArrayDeque<>();

    DatagramPacket receivePacket;
    DatagramSocket receiveSocket;

    // private final int SINGLE_DRONE_ID = 1;

    // private FireEvent activeEvent = null;

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

                DroneInfo drone = drones.get(droneId);
                if(drone == null) {
                    drone = new DroneInfo(droneId);
                    drones.put(droneId,drone);
                } else {
                    if (!drone.busy){
                        drone.available = true;
                    }
                }

                transition(SchedulerEvent.DRONE_POLL);
                tryDispatchIfPossible(adr, port);
            }

            case DRONE_DONE -> {
                DroneStatus status = (DroneStatus) msg.getPayload();
                int droneId = msg.get_source_id();
                System.out.println("[SCHEDULER] Drone completed task: " + status);

                DroneInfo drone = drones.get(droneId);
                if(drone != null){
                    drone.available = true;
                    drone.busy = false;
                    drone.assignedEvent = null;
                }

                transition(SchedulerEvent.DRONE_DONE_RECEIVED);

                // Mission finished; clear active job and attempt next
                // activeEvent = null;

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
        if (pendingEvents.isEmpty()) return;

        while(!pendingEvents.isEmpty()){
            DroneInfo drone = findAvailableDrone();
            if (drone == null) return;
            FireEvent event = pendingEvents.poll();
            dispatch(drone, event, adr, port);
        }
    }

    private void dispatch(DroneInfo drone, FireEvent event, InetAddress adr, int port){
        drone.available = false;
        drone.busy = true;
        drone.assignedEvent = event;

        DroneCommand cmd = new DroneCommand(
                "REQ-" + event.getTimestamp().toEpochMilli(),
                DroneCommandOptions.DISPATCH,
                event.getZoneId(),
                event.getSeverity()
        );

        byte[] msg = Message.droneTask(drone.droneId, cmd).toBytes();
        DatagramPacket msgPacket = new DatagramPacket(msg, msg.length, adr, port);
        try{
            receiveSocket.send(msgPacket);
        } catch (IOException e){
            throw new RuntimeException(e);
        }
        transition(SchedulerEvent.DISPATCH_SENT);
        System.out.println("[SCHEDULER] Dispatched Drone " + drone.droneId + " to zone " + event.getZoneId());
    }

    private void transition(SchedulerEvent event) {
        SchedulerState before = schedulerState;
        schedulerState = schedulerState.next(event);

        if (before != schedulerState) {
            System.out.println("[SCHEDULER] State: " + before + " -> " + schedulerState + " on " + event);
        }
    }
    private DroneInfo findAvailableDrone() {
        for (DroneInfo drone : drones.values()){
            if (drone.available) {
                return drone;
            }
        }
        return null;
    }

    public static void main(String args[]){
            MessageTransporter transport = new MessageTransporter();
            SystemCounts counts = null;
            SchedulerSubsystem scheduler = new SchedulerSubsystem(transport, counts);
            scheduler.receiveAndSend();
    }
}