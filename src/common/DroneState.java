package common;

import java.io.Serializable;

public enum DroneState implements Serializable {
    IDLE,
    EN_ROUTE,
    DROPPING,
    RETURNING,
    DONE
}
