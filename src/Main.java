import SubSystems.DroneSubsystem;
import SubSystems.FireIncidentSubsystem;
import SubSystems.SchedulerSubsystem;
import common.SystemCounts;

public class Main {
    public static void main(String[] args) {
        int numberOfDrones = 20;
        String inputFile = "src/Final_event_file_w26.csv";

        SystemCounts counts = new SystemCounts();
        counts.setTotalDrones(numberOfDrones);
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