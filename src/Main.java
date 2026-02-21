import MessageTransport.MessageTransporter;
import SubSystems.DroneSubsystem;
import SubSystems.FireIncidentSubsystem;
import SubSystems.SchedulerSubsystem;
import common.SystemCounts;

import javax.swing.*;

public class Main {
    public static void main(String[] args) {

        SystemCounts counts = new SystemCounts();

        counts.setTotalDrones(1);

        SwingUtilities.invokeLater(() -> new GUI(counts).setVisible(true));

        MessageTransporter transport = new MessageTransporter();

        Thread scheduler =
                new Thread(new SchedulerSubsystem(transport, counts));

        Thread fire =
                new Thread(new FireIncidentSubsystem(
                        transport, "src/input.csv"));

        Thread drone =
                new Thread(new DroneSubsystem(1, transport, counts));

        scheduler.start();
        fire.start();
        drone.start();
    }
}