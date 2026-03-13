package common;

import java.io.*;
import java.util.Objects;

// This class is the container for any message that is sent between the 3 subsystems
// payload variable contains the type: FireEvent, DroneCommand, DroneStatus, etc...
public final class Message implements Serializable {
    private final MessageType type;
    private final int source_id;      // drone_id or 0 for scheduler or fireIncident
    private final Object payload;

    public Message(MessageType type, int source_id, Object payload) {
        this.type = Objects.requireNonNull(type);
        this.source_id = source_id;
        this.payload = payload;
    }

    public MessageType getType() {
        return type;
    }

    public int get_source_id() {
        return source_id;
    }

    public Object getPayload() {
        return payload;
    }

    @Override
    public String toString() {
        return "Message{" +
                "type=" + type +
                ", source_id=" + source_id +
                ", payload=" + (payload == null ? "null"
                : payload.getClass().getSimpleName() + ":" + payload) +
                '}';
    }

    public byte[] toBytes() {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ObjectOutputStream out = new ObjectOutputStream(output);
            out.writeObject(this);
            out.flush();
            return output.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Message fromBytes(byte[] data) {
        try {
            ByteArrayInputStream input = new ByteArrayInputStream(data);
            ObjectInputStream in = new ObjectInputStream(input);
            return (Message) in.readObject();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // Message helpers
    public static Message fireEvent(FireEvent e) {
        return new Message(MessageType.FIRE_EVENT, 0, e);
    }

    public static Message dronePoll(int droneId) {
        return new Message(MessageType.DRONE_POLL, droneId, droneId);
    }

    public static Message droneTask(int droneId, DroneCommand cmd) {
        return new Message(MessageType.DRONE_TASK, droneId, cmd);
    }

    public static Message droneStatus(int droneId, DroneStatus status) {
        return new Message(MessageType.DRONE_STATUS, droneId, status);
    }

    public static Message droneDone(int droneId, DroneStatus status) {
        return new Message(MessageType.DRONE_DONE, droneId, status);
    }

    public static Message fireOut(FireEvent event) {
        return new Message(MessageType.FIRE_OUT, 0, event);
    }
}