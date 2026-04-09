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
    long previousEventMs = -1;

    private static final double TIME_SCALE = 60.0;

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
    public void run(){
        try {
            socket = new DatagramSocket(FIRE_INCIDENT_PORT);
            socket.setSoTimeout(500);
        } catch (SocketException e) {
            throw new RuntimeException(e);
        }

        if (common.DebugOutputFilter.isFireIncidentOutputActive())
            System.out.println("[FIRE] Fire Incident Subsystem started");

        try (BufferedReader br = new BufferedReader(new FileReader(csvFile))) {
            String line;
            int lineNumber = 0;

            while ((line = br.readLine()) != null) {
                lineNumber++;

                // Skip empty lines
                if (line.trim().isEmpty()) {
                    continue;
                }

                // Try to parse the CSV line
                FireEvent event = null;
                try {
                    event = parseCSVLine(line);
                } catch (Exception e) {
                    if (common.DebugOutputFilter.isFireIncidentOutputActive())
                        System.err.println("[FIRE] Error parsing line " + lineNumber + ": " + e.getMessage());
                    continue;  // Skip this line and continue
                }

                // Skip header row (parseCSVLine returns null for headers)
                if (event == null) {
                    if (common.DebugOutputFilter.isFireIncidentOutputActive())
                        System.out.println("[FIRE] Skipping header row");
                    continue;
                }
                long currentEventMs = event.getTimestamp().toEpochMilli();
                if(previousEventMs >= 0){
                    long delay = (long)((currentEventMs - previousEventMs) / TIME_SCALE);
                    if (delay > 0){
                        try {
                            Thread.sleep(delay);
                        } catch (InterruptedException e){
                            Thread.currentThread().interrupt();
                        }
                    }
                }
                previousEventMs = currentEventMs;

                if (common.DebugOutputFilter.isFireIncidentOutputActive())
                    System.out.println("[FIRE] Read event: " + event);

                Message msg = Message.fireEvent(event);
                byte[] data = msg.toBytes();

                DatagramPacket packet = new DatagramPacket(
                        data, data.length, InetAddress.getByName(SCHEDULER_HOST), SCHEDULER_PORT
                );
                socket.send(packet);

                if (common.DebugOutputFilter.isFireIncidentOutputActive())
                    System.out.println("[FIRE] Sent FIRE_EVENT to Scheduler");
                outstandingFires++;

                waitForAck();
            }

            if (common.DebugOutputFilter.isFireIncidentOutputActive())
                System.out.println("[FIRE] No more input events. Waiting for " + outstandingFires + " FIRE_OUT messages...");

            while (outstandingFires > 0) {
                waitForFireOut();
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        if (common.DebugOutputFilter.isFireIncidentOutputActive())
            System.out.println("[FIRE] No more events. Subsystem finished.");
    }

    /**
     * Waits until a FIRE_EVENT ACK is received from the scheduler.
     * Ignores timeouts and continues listening. FIRE_OUT messages may
     * arrive during this period and are processed to keep outstanding
     * fire counts accurate.
     */
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

            if (common.DebugOutputFilter.isFireIncidentOutputActive())
                System.out.println("[FIRE] Received response: " + response);

            if (response.getType() == MessageType.FIRE_EVENT) {
                return;
            }

            if (response.getType() == MessageType.FIRE_OUT) {
                outstandingFires--;
                FireEvent event = (FireEvent) response.getPayload();
                if (common.DebugOutputFilter.isFireIncidentOutputActive())
                    System.out.println("[FIRE] FIRE_OUT received while waiting for ACK: zone " + event.getZoneId());
            }
        }
    }

    /**
     * Waits once for a FIRE_OUT message. If none arrives before timeout,
     * the method returns silently. Used to track completion of outstanding
     * fire events without blocking indefinitely.
     */
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
            if (common.DebugOutputFilter.isFireIncidentOutputActive())
                System.out.println("[FIRE] FIRE_OUT received for zone " + event.getZoneId() +
                    ". Outstanding fires: " + outstandingFires);
        } else {
            if (common.DebugOutputFilter.isFireIncidentOutputActive())
                System.out.println("[FIRE] Ignored response: " + response);
        }
    }

    /**
     * Parses a CSV line and creates a FireEvent object.
     * Extended in Iteration 4 to parse fault information from the input file.
     *
     * CSV Format (with header):
     * Time,ZoneId,EventType,Severity,FaultType,FaultDelaySeconds
     * Example: 14:03:15,3,FIRE_DETECTED,HIGH,DRONE_STUCK,15
     *
     * Backwards compatible: if old format (4 fields) is detected, still works
     *
     * @param line A comma-separated string representing one fire event
     * @return A FireEvent object with all parsed information including fault data
     * @throws NumberFormatException if the CSV format is invalid
     */
    public FireEvent parseCSVLine(String line) {
        // Trim whitespace from line
        String trimmedLine = line.trim();

        // Skip header row (first line with "Time,ZoneId,EventType,Severity,FaultType,FaultDelaySeconds")
        if (trimmedLine.startsWith("Time") || trimmedLine.startsWith("time")) {
            return null;  // Signal to skip header
        }

        // Split by comma, allowing spaces around delimiters
        String[] parts = trimmedLine.split("\\s*,\\s*");

        // Validate minimum number of fields (4 for old format, 6 for new format)
        if (parts.length < 4) {
            throw new IllegalArgumentException(
                    "Invalid CSV format: expected at least 4 fields, got " + parts.length +
                            " from line: " + line
            );
        }

        try {
            // ===== Parse mandatory fields (common to both old and new format) =====

            // Field 0: Time in HH:mm:ss format
            String timeStr = parts[0].trim();

            // Field 1: Zone ID (must be a valid integer)
            int zoneId = Integer.parseInt(parts[1].trim());

            // Field 2: Event type (FIRE_DETECTED or DRONE_REQUEST)
            FireEventType eventType = FireEventType.valueOf(parts[2].trim().toUpperCase());

            // Field 3: Severity level (HIGH, MODERATE, LOW)
            Severity severity = Severity.valueOf(parts[3].trim().toUpperCase());

            // ===== Parse optional fault information (Iteration 4 addition) =====

            // Field 4: Fault type (NONE, DRONE_STUCK, NOZZLE_JAM, PACKET_LOSS)
            // Default to NONE if not provided (backwards compatible)
            FaultType faultType = FaultType.NONE;
            if (parts.length >= 5) {
                String faultTypeStr = parts[4].trim().toUpperCase();
                if (!faultTypeStr.isEmpty() && !faultTypeStr.equals("NONE")) {
                    try {
                        faultType = FaultType.valueOf(faultTypeStr);
                    } catch (IllegalArgumentException e) {
                        if (common.DebugOutputFilter.isFireIncidentOutputActive())
                            System.err.println("[FIRE] Warning: Unknown fault type '" + parts[4].trim() +
                                "', defaulting to NONE");
                        faultType = FaultType.NONE;
                    }
                }
            }

            // Field 5: Fault delay in seconds
            // Default to 0 if not provided
            int faultDelaySeconds = 0;
            if (parts.length >= 6) {
                String delayStr = parts[5].trim();
                if (!delayStr.isEmpty()) {
                    faultDelaySeconds = Integer.parseInt(delayStr);
                }
            }

            // Convert time string to Instant
            // Use current date + provided time
            Instant timestamp = null;
            try {
                // Parse "14:03:15" format
                java.time.LocalTime time = java.time.LocalTime.parse(
                        timeStr,
                        java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
                );

                // Combine with today's date to create full timestamp
                timestamp = time
                        .atDate(java.time.LocalDate.of(2000,1,1))
                        .atZone(java.time.ZoneId.systemDefault())
                        .toInstant();
            } catch (Exception timeParseEx) {
                if (common.DebugOutputFilter.isFireIncidentOutputActive())
                    System.err.println("[FIRE] Warning: Could not parse time '" + timeStr +
                        "', using current time");
                // Use Instant.now() as fallback
            }

            // Create and return FireEvent with all parsed data
            return new FireEvent(timestamp, zoneId, eventType, severity, faultType, faultDelaySeconds);

        } catch (NumberFormatException e) {
            throw new NumberFormatException(
                    "Failed to parse CSV line (number format error): " + line + "\n" +
                            "Cause: " + e.getMessage()
            );
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Failed to parse CSV line (invalid enum value): " + line + "\n" +
                            "Cause: " + e.getMessage()
            );
        }
    }

    public static void main(String[] args) {
        String csv = "src/Final_event_file_w26.csv";
        if (args.length > 0) {
            csv = args[0];
        }

        FireIncidentSubsystem fireIncidentSubsystem = new FireIncidentSubsystem(csv);
        fireIncidentSubsystem.run();
    }
}