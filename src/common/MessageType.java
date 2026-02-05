package common;

/*
*   FIRE_EVENT = FireIncident -> Scheduler
    DRONE_POLL = Drone -> Scheduler ("any work?")
    DRONE_TASK = Scheduler -> Drone
    DRONE_DONE = Drone -> Scheduler
* */

public enum MessageType {
    FIRE_EVENT,
    DRONE_POLL,
    DRONE_TASK,
    DRONE_DONE
}
