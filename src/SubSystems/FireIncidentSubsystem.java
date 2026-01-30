package SubSystems;

import MessageTransport.MessageTransporter;
import MessageTransport.SendAddress;
import common.*;

import java.io.BufferedReader;
import java.io.FileReader;
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
    private final MessageTransporter transport;

    // Path to the CSV input file
    private final String csvFile;

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
        System.out.println("[FIRE] Fire Incident Subsystem started");

        try (BufferedReader br = new BufferedReader(new FileReader(csvFile))) {

            String line;
            while ((line = br.readLine()) != null) {

                // Skip empty lines
                if (line.trim().isEmpty()) {
                    continue;
                }

                // Parse CSV line into a FireEvent object
                FireEvent event = parseCSVLine(line);
                System.out.println("[FIRE] Read event: " + event);

                // Create a FIRE_EVENT message using the Message factory method
                Message msg = Message.fireEvent(event);

                // Send the event to the Scheduler subsystem
                transport.send(SendAddress.SCHEDULER, msg);
                System.out.println("[FIRE] Sent FIRE_EVENT to Scheduler");

                // Wait for a response from the Scheduler
                // For now the content of the response is not important
                Message response =
                        transport.receive(SendAddress.FIRE_INCIDENT);

                System.out.println("[FIRE] Received response: " + response);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        System.out.println("[FIRE] No more events. Subsystem finished.");
    }

    /**
     * Parses a single line from the CSV file into a FireEvent object.
     *
     * CSV format:
     * time, zoneId, eventType, severity
     *
     * @param line one line from the CSV file
     * @return FireEvent created from the CSV data
     */
    private FireEvent parseCSVLine(String line) {
        String[] parts = line.split(",");

        // Use current time
        Instant timestamp = Instant.now();

        int zoneId = Integer.parseInt(parts[1].trim());

        FireEventType eventType =
                FireEventType.valueOf(parts[2].trim());

        Severity severity =
                Severity.valueOf(parts[3].trim());

        return new FireEvent(timestamp, zoneId, eventType, severity);
    }
}