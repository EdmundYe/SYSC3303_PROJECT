package common;

public enum DroneState {

    IDLE {
        @Override
        public DroneState next(DroneEvent event) {
            if (event == DroneEvent.TASK_RECEIVED)
                return EN_ROUTE;
            return this;
        }
    },

    EN_ROUTE {
        @Override
        public DroneState next(DroneEvent event) {
            if (event == DroneEvent.ARRIVED)
                return DROPPING;
            return this;
        }
    },

    DROPPING {
        @Override
        public DroneState next(DroneEvent event) {
            if (event == DroneEvent.DROP_COMPLETE)
                return RETURNING;
            return this;
        }
    },

    RETURNING {
        @Override
        public DroneState next(DroneEvent event) {
            if (event == DroneEvent.RETURN_COMPLETE)
                return DONE;
            return this;
        }
    },

    DONE {
        @Override
        public DroneState next(DroneEvent event) {
            return IDLE;
        }
    };

    public abstract DroneState next(DroneEvent event);
}
