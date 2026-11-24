package net.tfminecraft.vehicleframework.vehicles.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

class ParkedRestTest {
    private final World world = mock(World.class);
    private final ParkedRest rest = new ParkedRest();

    @Test
    void holdsOnlyAtTheExactSpotForUnderOneRecheckWindow() {
        Location spot = new Location(world, 8.25, 64, -3.5);
        assertFalse(rest.isSettled());
        assertFalse(rest.holds(spot, 0));
        rest.settle(spot, 100);
        assertTrue(rest.isSettled());
        for (long tick = 100; tick < 100 + ParkedRest.RECHECK_TICKS; tick++) {
            assertTrue(rest.holds(spot.clone(), tick), "tick " + tick);
        }
        assertFalse(rest.holds(spot, 100 + ParkedRest.RECHECK_TICKS), "a full read is due");
        assertFalse(rest.holds(new Location(world, 8.25, 64.0001, -3.5), 101));
        assertFalse(rest.holds(new Location(world, 8.26, 64, -3.5), 101));
        assertFalse(rest.holds(new Location(world, 8.25, 64, -3.4), 101));
        assertFalse(rest.holds(new Location(mock(World.class), 8.25, 64, -3.5), 101));
        rest.reset();
        assertFalse(rest.isSettled());
        assertFalse(rest.holds(spot, 101));
    }
}
