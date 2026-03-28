package SubSystems;

import MessageTransport.MessageTransporter;
import MessageTransport.SendAddress;
import common.*;

import java.io.BufferedReader;
import java.io.FileReader;
import java.net.*;
import java.time.Instant;

/**
 * SubSystems.FireIncidentSubsystem
 *
 * This subsystem is responsible for:
 * 1. Reading fire incident events from the input CSV file
 * 2. Converting each line into a FireEvent object
 * 3. Sending FIRE_EVENT messages to the Scheduler subsystem
 * 4. Waiting for a response from the Scheduler
 *
 * For now this subsystem does NOT perform time synchronization
 * and processes events sequentially.
 */
public class FireIncidentSubsystem implements Runnable {

    // Transport mechanism used to communicate with other subsystems
    private MessageTransporter transport = null;

    private static final String SCHEDULER_HOST = "localhost";
    private static final int SCHEDULER_PORT = 6000;
    private static final int FIRE_INCIDENT_PORT = 7000;

    private final String csvFile; // Path to the CSV input file
    private int outstandingFires = 0;
    private DatagramSocket socket;

    public FireIncidentSubsystem(String csvFile) {
        this.csvFile = csvFile;
    }

    /**
     * Constructs a SubSystems.FireIncidentSubsystem.
     *
     * @param transport shared MessageTransporter instance
     * @param csvFile   path to the fire incident input file
     */
    public FireIncidentSubsystem(MessageTransporter transport, String csvFile) {
        this.transport = transport;
        this.csvFile = csvFile;
    }

    /**
     * Main execution loop of the Fire Incident Subsystem.
     * Reads the input file line-by-line and sends each event
     * to the Scheduler subsystem.
     */
    @Override
    public void run() {
        try {
            socket = new DatagramSocket(FIRE_INCIDENT_PORT);
            socket.setSoTimeout(500);
        } catch (SocketException e) {
            throw new RuntimeException(e);
        }

        System.out.println("[FIRE] Fire Incident Subsystem started");

        try (BufferedReader br = new BufferedReader(new FileReader(csvFile))) {
            String line;

            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }

                FireEvent event = parseCSVLine(line);
                System.out.println("[FIRE] Read event: " + event);

                Message msg = Message.fireEvent(event);
                byte[] data = msg.toBytes();

                DatagramPacket packet = new DatagramPacket(
                        data, data.length, InetAddress.getByName(SCHEDULER_HOST), SCHEDULER_PORT
                );
                socket.send(packet);

                System.out.println("[FIRE] Sent FIRE_EVENT to Scheduler");
                outstandingFires++;

                waitForAck();
            }

            System.out.println("[FIRE] No more input events. Waiting for " + outstandingFires + " FIRE_OUT messages...");

            while (outstandingFires > 0) {
                waitForFireOut();
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        System.out.println("[FIRE] No more events. Subsystem finished.");
    }

    private void waitForAck() throws Exception {
        while (true) {
            byte[] buf = new byte[4096];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);

            try {
                socket.receive(packet);
            } catch (SocketTimeoutException ignored) {
                continue;
            }

            Message response = Message.fromBytes(
                    java.util.Arrays.copyOf(packet.getData(), packet.getLength())
            );

            System.out.println("[FIRE] Received response: " + response);

            if (response.getType() == MessageType.FIRE_EVENT) {
                return;
            }

            if (response.getType() == MessageType.FIRE_OUT) {
                outstandingFires--;
                FireEvent event = (FireEvent) response.getPayload();
                System.out.println("[FIRE] FIRE_OUT received while waiting for ACK: zone " + event.getZoneId());
            }
        }
    }

    private void waitForFireOut() throws Exception {
        byte[] buf = new byte[4096];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);

        try {
            socket.receive(packet);
        } catch (SocketTimeoutException ignored) {
            return;
        }

        Message response = Message.fromBytes(
                java.util.Arrays.copyOf(packet.getData(), packet.getLength())
        );

        if (response.getType() == MessageType.FIRE_OUT) {
            FireEvent event = (FireEvent) response.getPayload();
            outstandingFires--;
            System.out.println("[FIRE] FIRE_OUT received for zone " + event.getZoneId() +
                    ". Outstanding fires: " + outstandingFires);
        } else {
            System.out.println("[FIRE] Ignored response: " + response);
        }
    }

    /**
     * CSV format:
     * time, zoneId, eventType, severity
     */
    public FireEvent parseCSVLine(String line) {
        String[] parts = line.split("\\s*,\\s*");

        Instant timestamp = Instant.now();
        int zoneId = Integer.parseInt(parts[1].trim());
        FireEventType eventType = FireEventType.valueOf(parts[2].trim());
        Severity severity = Severity.valueOf(parts[3].trim());
        FaultType faultType = FaultType.valueOf(parts[4].trim());
        int faultDelayType = Integer.parseInt(parts[5].trim());

        return new FireEvent(timestamp, zoneId, eventType, severity, faultType, faultDelayType);
    }

    public static void main(String[] args) {
        String csv = "src/input.csv";
        if (args.length > 0) {
            csv = args[0];
        }

        FireIncidentSubsystem fireIncidentSubsystem = new FireIncidentSubsystem(csv);
        fireIncidentSubsystem.run();
    }
}