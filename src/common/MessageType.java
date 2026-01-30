package common;

import java.io.Serializable;

/*
*   FIRE_EVENT = FireIncident -> Scheduler
    DRONE_POLL = Drone -> Scheduler ("any work?")
    DRONE_TASK = Scheduler -> Drone
    DRONE_DONE = Drone -> Scheduler
* */

public enum MessageType implements Serializable {
    FIRE_EVENT,
    DRONE_POLL,
    DRONE_TASK,
    DRONE_DONE
}
