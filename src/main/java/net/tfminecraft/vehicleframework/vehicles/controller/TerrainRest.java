package net.tfminecraft.vehicleframework.vehicles.controller;

/**
 * Lets a terrain-following vehicle that stands idle stop re-reading the ground every tick.
 * The ground step traces rays under every wheel and teleports the vehicle onto the result, even
 * when the result is the spot it already stands on. Once {@link #SETTLE_STEPS} steps in a row
 * leave the vehicle exactly where it was, with the same tilt, the step is left out until the
 * vehicle is found anywhere else (a push, a fall, a piston) or is no longer idle. A full step
 * still runs every {@link #RECHECK_TICKS} ticks, so a block placed or removed under a vehicle
 * that nothing moves is seen within that time.
 */
final class TerrainRest {
	/** Still steps before the vehicle counts as settled; also lets the tilt smoothing finish. */
	static final int SETTLE_STEPS = 20;
	/** While settled, one tick in this many runs the full ground step. */
	static final int RECHECK_TICKS = 20;
	private static final double POSITION_EPSILON = 1e-4;
	private static final double TILT_EPSILON = 1e-3;

	private int still;
	private int skipped;
	private double x;
	private double y;
	private double z;
	private boolean tilted;
	private float pitch;
	private float roll;

	/** Whether this tick's ground step can be left out for a vehicle now standing at this position. */
	boolean skip(double currentX, double currentY, double currentZ) {
		if (still < SETTLE_STEPS) {
			return false;
		}
		if (!at(currentX, currentY, currentZ)) {
			reset();
			return false;
		}
		if (skipped >= RECHECK_TICKS - 1) {
			skipped = 0;
			return false;
		}
		skipped++;
		return true;
	}

	/**
	 * Records one full ground step: where the vehicle stood before it, where the step put it, whether
	 * the step gave it any velocity, and the tilt it asked for ({@code hasTilt} false for none).
	 */
	void observe(
			double beforeX, double beforeY, double beforeZ,
			double destX, double destY, double destZ,
			boolean moving, boolean hasTilt, float pitchDeg, float rollDeg) {
		boolean stayed = !moving
				&& near(beforeX, destX) && near(beforeY, destY) && near(beforeZ, destZ);
		boolean sameAsLast = still == 0
				|| (at(destX, destY, destZ) && tilted == hasTilt
						&& (!hasTilt || (Math.abs(pitch - pitchDeg) <= TILT_EPSILON
								&& Math.abs(roll - rollDeg) <= TILT_EPSILON)));
		if (!stayed) {
			still = 0;
		} else if (sameAsLast) {
			still = Math.min(SETTLE_STEPS, still + 1);
		} else {
			still = 1;
		}
		if (still < SETTLE_STEPS) {
			skipped = 0;
		}
		x = destX;
		y = destY;
		z = destZ;
		tilted = hasTilt;
		pitch = pitchDeg;
		roll = rollDeg;
	}

	/** Forgets the settled spot: the next {@link #SETTLE_STEPS} steps all run. */
	void reset() {
		still = 0;
		skipped = 0;
	}

	boolean settled() {
		return still >= SETTLE_STEPS;
	}

	private boolean at(double px, double py, double pz) {
		return near(px, x) && near(py, y) && near(pz, z);
	}

	private static boolean near(double a, double b) {
		return Math.abs(a - b) <= POSITION_EPSILON;
	}
}
