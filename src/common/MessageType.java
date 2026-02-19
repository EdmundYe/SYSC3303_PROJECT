package common;

/*
FIRE_EVENT    = FireIncident -> Scheduler
DRONE_POLL    = Drone -> Scheduler ("any work?")
DRONE_TASK    = Scheduler -> Drone
DRONE_STATUS  = Drone -> Scheduler (state updates)
DRONE_DONE    = Drone -> Scheduler (mission completion)
FIRE_OUT      = Scheduler -> FireIncident (extinguished notification)
*/

public enum MessageType {
    FIRE_EVENT,
    DRONE_POLL,
    DRONE_TASK,
    DRONE_STATUS,
    DRONE_DONE,
    FIRE_OUT
}
