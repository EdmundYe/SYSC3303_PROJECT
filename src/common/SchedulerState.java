package common;


// Drone provides only POLL and DONE (no status/arrival), so scheduler can only track "busy vs idle"
public enum SchedulerState {

    IDLE {
        @Override
        public SchedulerState next(SchedulerEvent event) {
            if (event == SchedulerEvent.DISPATCH_SENT) return WAITING_FOR_DONE;
            return this;
        }
    },

    WAITING_FOR_DONE {
        @Override
        public SchedulerState next(SchedulerEvent event) {
            if (event == SchedulerEvent.DRONE_DONE_RECEIVED) return IDLE;
            return this;
        }
    };

    public abstract SchedulerState next(SchedulerEvent event);
}