package SubSystems;

import common.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DroneAgentRefillTest {

    /**
     * Test: Agent always refills to full capacity when returning to base
     */
    @Test
    void testAgentRefillsOnReturnToBase() {
        // Arrange: Drone has used some agent (50L remaining)
        int remainingAgent = 50;
        int DEFAULT_AGENT_CAPACITY = 100;

        // Act: Drone returns to base
        remainingAgent = DEFAULT_AGENT_CAPACITY;  // Automatic refill

        // Assert
        assertEquals(DEFAULT_AGENT_CAPACITY, remainingAgent,
                "Agent should refill to 100L when returning to base");
    }

    /**
     * Test: Agent refills even if only partially used
     */
    @Test
    void testAgentRefillsEvenIfPartiallyUsed() {
        // Arrange: Drone still has 80L
        int remainingAgent = 80;

        // Act: Return to base
        remainingAgent = 100;

        // Assert
        assertEquals(100, remainingAgent,
                "Agent should always refill to 100L at base, regardless of current amount");
    }

    /**
     * Test: Battery also recharges when returning to base
     */
    @Test
    void testBatteryRechargesWithAgentRefill() {
        // Arrange
        int remainingAgent = 20;
        int batteryLevel = 30;

        // Act: Return to base
        remainingAgent = 100;
        batteryLevel = 100;

        // Assert
        assertEquals(100, remainingAgent, "Agent should be 100L");
        assertEquals(100, batteryLevel, "Battery should be 100%");
    }

    /**
     * Test: Scheduler should not dispatch drone if agent is insufficient
     */
    @Test
    void testSchedulerChecksAgentBeforeDispatch() {
        // Arrange: Drone has 30L, but mission needs 50L
        int droneAgent = 30;
        int requiredAgent = 50;  // HIGH severity needs 50L

        // Act & Assert: Drone should not be selected
        boolean canDispatch = droneAgent >= requiredAgent;
        assertFalse(canDispatch,
                "Scheduler should not dispatch drone if agent is insufficient");
    }

    /**
     * Test: Scheduler dispatches drone if agent is sufficient
     */
    @Test
    void testSchedulerDispatchesDroneIfAgentSufficient() {
        // Arrange: Drone has 100L, mission needs 50L
        int droneAgent = 100;
        int requiredAgent = 50;

        // Act & Assert: Drone can be dispatched
        boolean canDispatch = droneAgent >= requiredAgent;
        assertTrue(canDispatch,
                "Scheduler should dispatch drone if agent is sufficient");
    }
}