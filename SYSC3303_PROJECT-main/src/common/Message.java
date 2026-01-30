package common;

import java.io.Serializable;
import java.util.Objects;

//This class is the container for any message that is sent between the 3 subsystems
//payload variable contains the type: FireEvent, DroneCommand, DroneStatus, etc...
public final class Message implements Serializable {
    private final MessageType type;
    private final int source_id;       //drone_id or 0 for scheduler or fireIncident
    private final Object payload;     // keep generic for Iteration 1

    public Message(MessageType type, int source_id, Object payload) {
        this.type = Objects.requireNonNull(type);
        this.source_id = source_id;
        this.payload = payload;
    }

    public MessageType getType() { return type; }
    public int get_source_id() { return source_id; }
    public Object getPayload() { return payload; }

    @Override
    public String toString() {
        return "Message{" +
                "type=" + type +
                ", source_id=" + source_id +
                ", payload=" + (payload == null ? "null" : payload.getClass().getSimpleName() + ":" + payload) +
                '}';
    }

    //functions to generate messages based on the event
    public static Message fireEvent(FireEvent e) {
        return new Message(MessageType.FIRE_EVENT,0, e);
    }

    public static Message dronePoll(int droneId) {
        return new Message(MessageType.DRONE_POLL, 0, droneId);
    }

    public static Message droneTask(int drone_id, DroneCommand cmd) {
        return new Message(MessageType.DRONE_TASK, drone_id, cmd);
    }

    public static Message droneDone(int drone_id, DroneStatus status) {
        return new Message(MessageType.DRONE_DONE, drone_id, status);
    }
}