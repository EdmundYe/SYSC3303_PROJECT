import MessageTransport.MessageTransporter;

import common.*;
import MessageTransport.SendAddress;
import MessageTransport.MessageTransporter;

import java.util.ArrayDeque;
import java.util.Queue;

public class SchedulerSubsystem implements Runnable {

    private final MessageTransporter transport;
    private final Queue<FireEvent> pendingEvents = new ArrayDeque<>();

    public SchedulerSubsystem(MessageTransporter transport) {
        this.transport = transport;
    }

    public void run()
    {
        System.out.println("[SCHEDULER] Started");
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Message msg = transport.receive(SendAddress.SCHEDULER);
                handle(msg);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.out.println("[SCHEDULER] Stopped");
    }

    //based off the message type it will generate the specified message
    private void handle(Message msg) {
        switch (msg.getType()) {

        }
    }
}
