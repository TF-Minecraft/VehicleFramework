package net.tfminecraft.vehicleframework.vehicles.controller;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * Lets a parked floating vehicle (see {@link VehicleMovementController#isParked}) stop reading the
 * blocks around it every tick. Once its float scan has found no water, and the state checks of that
 * same tick kept it on the ground, both are taken as still true while it stands at exactly that
 * spot, for at most {@link #RECHECK_TICKS} ticks. Anything that moves it, or ends its idling, makes
 * the next tick read everything again, so only water or blocks that change under a vehicle nothing
 * touches are seen up to a second late.
 */
final class ParkedRest {
	/** A full read runs at least once in this many ticks. */
	static final int RECHECK_TICKS = 20;

	private boolean settled;
	private World world;
	private double x;
	private double y;
	private double z;
	private long since;

	/** Whether a dry spot is remembered at all; cheap, for the per-tick question. */
	boolean isSettled() {
		return settled;
	}

	/** Whether the vehicle still stands where the last full read found it dry, recently enough. */
	boolean holds(Location at, long now) {
		return settled && now - since < RECHECK_TICKS && at.getWorld() == world
				&& at.getX() == x && at.getY() == y && at.getZ() == z;
	}

	/** Records a full read on tick {@code now} that found the vehicle dry where it stands. */
	void settle(Location at, long now) {
		settled = true;
		world = at.getWorld();
		x = at.getX();
		y = at.getY();
		z = at.getZ();
		since = now;
	}

	/** Forgets the spot: the next tick reads everything. */
	void reset() {
		settled = false;
		world = null;
	}
}
