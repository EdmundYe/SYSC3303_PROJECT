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
            // Transition to FAULTED if fault detected during travel
            if (event == DroneEvent.FAULT_DETECTED)
                return FAULTED;
            return this;
        }
    },

    DROPPING {
        @Override
        public DroneState next(DroneEvent event) {
            if (event == DroneEvent.DROP_COMPLETE)
                return RETURNING;
            // Transition to FAULTED if nozzle or other fault detected
            if (event == DroneEvent.FAULT_DETECTED)
                return FAULTED;
            return this;
        }
    },

    RETURNING {
        @Override
        public DroneState next(DroneEvent event) {
            if (event == DroneEvent.RETURN_COMPLETE)
                return DONE;
            if (event == DroneEvent.FAULT_DETECTED)
                return FAULTED;
            return this;
        }
    },

    /**
     * New state in Iteration 4: Drone encountered a recoverable fault.
     * Example: Drone stuck mid-flight - can recover after timeout
     */
    FAULTED {
        @Override
        public DroneState next(DroneEvent event) {
            // Recover from temporary fault
            if (event == DroneEvent.RECOVERED)
                return IDLE;
            // Hard fault detected - permanent offline
            if (event == DroneEvent.HARD_FAULT)
                return OFFLINE;
            return this;
        }
    },

    /**
     * New state in Iteration 4: Drone encountered an unrecoverable hard fault.
     * Example: Nozzle permanently jammed - drone must be taken offline
     */
    OFFLINE {
        @Override
        public DroneState next(DroneEvent event) {
            // Drone stays offline - no state transitions possible
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
