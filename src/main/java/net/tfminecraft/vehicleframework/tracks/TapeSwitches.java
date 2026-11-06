package net.tfminecraft.vehicleframework.tracks;

import java.util.Map;
import java.util.UUID;

import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;

/**
 * Sets the switches ahead of a tape-driven train the way the tape went. The tape knows
 * which track the train was on next; a facing switch leading onto that track is thrown to
 * diverge, every other facing switch in reach is set straight through.
 */
public final class TapeSwitches {

	/** How close to a frog the last sample before a change of track must be. */
	static final double FROG_REACH = 3;
	/** Samples looked through for a turn back before a change of track. */
	static final int LOOK_AHEAD = 512;

	private TapeSwitches() {
	}

	// Every sample from the cursor up to the change travels the same way: no reversal between.
	private static boolean straightTo(ThrottleTape tape, int cursor, int change, int direction) {
		int steps = Math.floorMod(change - cursor, tape.size());
		if (steps > LOOK_AHEAD) {
			return false;
		}
		for (int i = 0; i < steps; i++) {
			ThrottleTape.Sample sample = tape.sample(cursor + i);
			if (sample.throttle != 0 && sample.sign != direction) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Aligns the facing switches within {@code window} blocks ahead of {@code s}, travelling
	 * {@code direction} along {@code spline}. Switches this train is already on keep the way
	 * it is on them ({@code held}). True when a switch within {@code holdWithin} is set
	 * wrong and another train on it stops it being thrown: the train should wait.
	 */
	public static boolean align(TrackRegistry registry, ThrottleTape tape, int cursor, TrackSpline spline, double s,
			int direction, double window, double holdWithin, Map<UUID, Boolean> held, ActiveVehicle train) {
		if (registry == null || tape == null || tape.isEmpty() || spline == null || direction == 0) {
			return false;
		}
		int change = tape.nextSplineChange(cursor);
		String next = change < 0 || !straightTo(tape, cursor, change, direction) ? null
				: tape.sample(change).resolvedSpline(tape.getSplineId());
		double leaveAt = change < 0 ? 0 : tape.sample(change - 1).s;
		boolean hold = false;
		for (TrackJunction junction : registry.junctionsOn(spline.getId())) {
			if (junction.branchSplineId == null || junction.facingSign != direction || held.containsKey(junction.id)) {
				continue;
			}
			double ahead = TrackJunctionTravel.ahead(s, junction.s, direction, spline.isLoop(), spline.length());
			if (ahead < -1e-9 || ahead > window) {
				continue;
			}
			// The tape leaves this track here, at this frog, without turning back first.
			boolean diverge = junction.branchSplineId.toString().equalsIgnoreCase(next)
					&& Math.abs(ThrottleTape.along(leaveAt, junction.s, spline.length(), spline.isLoop())) <= FROG_REACH;
			if (junction.thrown == diverge) {
				continue;
			}
			if (registry.setThrown(junction.id, diverge)) {
				RecorderLog.junction(train, diverge, junction.id, "tape");
			} else if (ahead <= holdWithin) {
				hold = true;
			}
		}
		return hold;
	}
}
