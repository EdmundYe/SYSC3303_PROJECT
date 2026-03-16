import SubSystems.DroneSubsystem;
import SubSystems.FireIncidentSubsystem;
import SubSystems.SchedulerSubsystem;
import common.SystemCounts;

import javax.swing.SwingUtilities;

public class Main {
    public static void main(String[] args) {
        int numberOfDrones = 3;
        String inputFile = "src/input.csv";

        SystemCounts counts = new SystemCounts();
        counts.setTotalDrones(numberOfDrones);

        try {
            SwingUtilities.invokeAndWait(() -> {
                GUI gui = new GUI(counts);
                gui.setVisible(true);
            });
        } catch (Exception e) {
            throw new RuntimeException("Failed to start GUI", e);
        }


        Thread schedulerThread = new Thread(new SchedulerSubsystem(counts));

        schedulerThread.start();

        for (int i = 1; i <= numberOfDrones; i++) {
            Thread droneThread = new Thread(
                    new DroneSubsystem(i, null, counts),
                    "Drone-" + i + "-Thread"
            );
            droneThread.start();
        }

        Thread fireThread = new Thread(
                new FireIncidentSubsystem(inputFile),
                "FireIncident-Thread"
        );

        fireThread.start();
    }
}