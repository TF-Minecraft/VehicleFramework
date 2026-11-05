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

	private TapeSwitches() {
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
		String next = change < 0 ? null : tape.sample(change).resolvedSpline(tape.getSplineId());
		boolean hold = false;
		for (TrackJunction junction : registry.junctionsOn(spline.getId())) {
			if (junction.branchSplineId == null || junction.facingSign != direction || held.containsKey(junction.id)) {
				continue;
			}
			double ahead = TrackJunctionTravel.ahead(s, junction.s, direction, spline.isLoop(), spline.length());
			if (ahead < -1e-9 || ahead > window) {
				continue;
			}
			boolean diverge = junction.branchSplineId.toString().equalsIgnoreCase(next);
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
