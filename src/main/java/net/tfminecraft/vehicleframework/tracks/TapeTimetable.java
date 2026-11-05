package net.tfminecraft.vehicleframework.tracks;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads a throttle tape as a timetable. Every sample lasted its held ticks while recording,
 * so a train running "on paper" (unloaded) is where the tape says the locomotive was that
 * many ticks into the run, without looking at a single block.
 */
public final class TapeTimetable {

	private TapeTimetable() {
	}

	/** A point in the timetable: the sample in effect and how long it has been. */
	public record Clock(int cursor, int ticksAt) {
	}

	/**
	 * After moving the clock on: where it is, the largest throttle used on the way (zero
	 * when the train stood still all along), and the recorded stops it arrived at.
	 */
	public record Advance(Clock clock, int maxThrottle, List<Integer> stops) {
	}

	/** Where the locomotive was at a point in the timetable. */
	public record Place(String splineId, double s, int orientation, int sign, int throttle) {
	}

	/** Moves the clock {@code ticks} on, round the end of the tape to its start. */
	public static Advance advance(ThrottleTape tape, Clock from, int ticks, int minStopTicks) {
		if (tape == null || tape.isEmpty()) {
			return new Advance(from, 0, List.of());
		}
		int n = tape.size();
		int cursor = Math.floorMod(from.cursor(), n);
		int at = Math.max(0, from.ticksAt());
		int left = Math.max(0, ticks);
		int max = 0;
		List<Integer> stops = new ArrayList<>();
		while (left > 0) {
			ThrottleTape.Sample sample = tape.sample(cursor);
			int remaining = sample.holdTicks - at;
			if (remaining <= 0) {
				cursor = (cursor + 1) % n;
				at = 0;
				ThrottleTape.Sample next = tape.sample(cursor);
				if (next.throttle == 0 && next.holdTicks >= Math.max(1, minStopTicks)) {
					stops.add(cursor);
				}
				continue;
			}
			int take = Math.min(remaining, left);
			at += take;
			left -= take;
			max = Math.max(max, Math.abs(sample.throttle));
		}
		return new Advance(new Clock(cursor, at), max, stops);
	}

	/**
	 * Where the locomotive was at {@code clock}: between the sample in effect and the next
	 * one in proportion to the ticks held, when both are on the same track.
	 */
	public static Place place(ThrottleTape tape, Clock clock, TrackRegistry registry) {
		ThrottleTape.Sample at = tape.sample(clock.cursor());
		ThrottleTape.Sample next = tape.sample(clock.cursor() + 1);
		String spline = at.resolvedSpline(tape.getSplineId());
		double s = at.s;
		TrackSpline track = track(registry, spline);
		if (track != null && spline.equalsIgnoreCase(next.resolvedSpline(tape.getSplineId()))) {
			double d = ThrottleTape.along(at.s, next.s, track.length(), track.isLoop());
			double share = Math.min(1.0, Math.max(0, clock.ticksAt()) / (double) at.holdTicks);
			s = TrackJunction.wrapS(at.s + d * share, track.length(), track.isLoop());
		}
		return new Place(spline, s, at.orientation, at.sign, at.throttle);
	}

	/** The track with this id, or null if it is gone or the id is not one. */
	public static TrackSpline track(TrackRegistry registry, String id) {
		if (registry == null || id == null) {
			return null;
		}
		try {
			return registry.get(java.util.UUID.fromString(id)).orElse(null);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
