package MessageTransport;

import common.Message;

public interface TransportUtility {
    void send(SendAddress to, Message msg);
    Message receive(SendAddress me) throws InterruptedException; // will need blocking
}

