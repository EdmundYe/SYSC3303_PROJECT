package common;

public enum SchedulerState {

    IDLE {
        @Override
        public SchedulerState next(SchedulerEvent event) {
            if (event == SchedulerEvent.FIRE_RECEIVED) return SENDING_DRONES;
            return this;
        }
    },

    SENDING_DRONES {
        @Override
        public SchedulerState next(SchedulerEvent event) {
            if (event == SchedulerEvent.DISPATCH_SENT) return WAITING_FOR_DRONES;
            return this;
        }
    },

    WAITING_FOR_DRONES {
        @Override
        public SchedulerState next(SchedulerEvent event) {
            if (event == SchedulerEvent.DRONE_DONE_RECEIVED) return SENDING_DRONES;
            if (event == SchedulerEvent.DRONE_POLL) return SENDING_DRONES;
            if (event == SchedulerEvent.DRONE_STATUS_RECEIVED) return this;
            if (event == SchedulerEvent.FIRE_RECEIVED) return this;
            return this;
        }
    };

    public abstract SchedulerState next(SchedulerEvent event);
}