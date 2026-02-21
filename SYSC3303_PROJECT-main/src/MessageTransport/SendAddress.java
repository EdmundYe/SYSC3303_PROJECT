package MessageTransport;

//Determine who's queue you want to send messages to
public enum SendAddress {
    SCHEDULER,
    FIRE_INCIDENT,
    DRONE
}
