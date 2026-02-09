package common;

public enum DroneState {
    IDLE {
        @Override
        public DroneState next() { return IDLE; }
    },
    EN_ROUTE {
        @Override
        public DroneState next() { return DROPPING; }
    },
    DROPPING {
        @Override
        public DroneState next() { return RETURNING; }
    },
    RETURNING {
        @Override
        public DroneState next() { return DONE; }
    },
    DONE {
        @Override
        public DroneState next() { return IDLE; }
    };

    public abstract DroneState next();
}
