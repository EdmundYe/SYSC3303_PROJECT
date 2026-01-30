import MessageTransport.MessageTransporter;

public class Main {
    public static void main(String[] args) {

        MessageTransporter transport = new MessageTransporter();

        Thread scheduler =
                new Thread(new SchedulerSubsystem(transport));

        Thread fire =
                new Thread(new FireIncidentSubsystem(
                        transport, "input.csv"));

        Thread drone =
                new Thread(new DroneSubsystem(1, transport));

        scheduler.start();
        fire.start();
        drone.start();
    }
}
