package MessageTransport;

import common.Message;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public final class MessageTransporter implements TransportUtility {
    private final Map<SendAddress, BlockingQueue<Message>> inboxes = new EnumMap<>(SendAddress.class);

    //create bounded queues for the scheduler, drones and fireSubsystem
    public MessageTransporter() {
        for (SendAddress addr : SendAddress.values()) {
            inboxes.put(addr, new LinkedBlockingQueue<>());
        }
    }

    @Override
    public void send(SendAddress to, Message msg) {
        BlockingQueue<Message> q = inboxes.get(to);
        if (q == null) throw new IllegalArgumentException("Unknown address: " + to);
        q.add(msg);
    }

    @Override
    public Message receive(SendAddress me) throws InterruptedException {
        BlockingQueue<Message> q = inboxes.get(me);
        if (q == null) throw new IllegalArgumentException("Unknown address: " + me);
        return q.take();
    }
}
