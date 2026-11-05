package net.tfminecraft.vehicleframework.tracks;

/**
 * Plays a throttle tape in the order it was recorded. A cursor points at the sample in
 * effect; it moves on when the train reaches the next sample's place on the track, or,
 * where the train stood still while recording (a stop, or the throttle moving at rest),
 * once the sample's held ticks have passed. A shuttle passes the same place both ways, so
 * position alone cannot tell which part of the tape applies; the cursor can.
 */
public final class TapePlayback {
	/** How close to the next sample's place counts as having reached it. */
	static final double REACH = 0.3;
	/** Consecutive samples closer than this were taken with the train (almost) at rest. */
	public static final double STATIONARY = 0.25;
	/** Further than this from the tape's place, the train looks for its place again. */
	public static final double RESYNC_DISTANCE = 8;
	/** Samples skipped in one tick at most; a fast train passes a few, never this many. */
	static final int MAX_ADVANCE = 64;

	/** Samples looked through for a stop to brake for. */
	static final int BRAKE_LOOK_AHEAD = 64;
	/** Stops further than this are too far to brake for yet. */
	static final double BRAKE_DISTANCE = 64;
	/** Half a block's slack, so a recorded slowing down is not cut short. */
	static final double BRAKE_SLACK = 0.5;
	/** Ticks between looks for the train's place when it is off the tape's tracks. */
	static final int RETRY_TICKS = 20;

	private int cursor = -1;
	private int ticksAt;
	private int retryIn;

	/** What the tape asks for this tick. */
	public record Step(int target, int cursor, boolean atStop, boolean offRoute) {
	}

	public int getCursor() {
		return cursor;
	}

	public int getTicksAt() {
		return ticksAt;
	}

	/** Resumes from a saved cursor; it is checked against the train on the next step. */
	public void setCursor(int cursor, int ticksAt) {
		this.cursor = cursor;
		this.ticksAt = Math.max(0, ticksAt);
		retryIn = 0;
	}

	public void reset() {
		setCursor(-1, 0);
	}

	/**
	 * One tick of playback for a train at {@code s} on {@code spline} ({@code length} long),
	 * travelling with {@code sign} along it, its body facing {@code orientation}. A recorded
	 * stop is one at least {@code minStopTicks} long with the throttle at zero.
	 */
	public Step step(ThrottleTape tape, String spline, double s, double length, boolean loop, int sign,
			int orientation, int minStopTicks) {
		return step(tape, spline, s, length, loop, sign, orientation, minStopTicks, 0);
	}

	/**
	 * As above, braking for the next recorded stop: {@code perThrottle} is how far the train
	 * goes in a tick per throttle point, and the throttle comes down one point a tick. Where
	 * the tape jumps from moving to standing (where it runs from its end into its first
	 * sample, or at a track end), the train still stops at the stop, not past it.
	 */
	public Step step(ThrottleTape tape, String spline, double s, double length, boolean loop, int sign,
			int orientation, int minStopTicks, double perThrottle) {
		if (tape == null || tape.isEmpty() || spline == null) {
			return new Step(0, cursor, false, true);
		}
		int n = tape.size();
		if (cursor < 0 || cursor >= n || lost(tape, spline, s, length, loop)) {
			// Looking through the whole tape is slow: off the tape, look again only now and then.
			if (retryIn-- > 0) {
				return new Step(0, cursor, false, true);
			}
			int found = tape.nearestIndex(spline, s, sign, cursor, RESYNC_DISTANCE / 2, length, loop);
			if (found < 0) {
				retryIn = RETRY_TICKS;
				cursor = -1;
				return new Step(0, cursor, false, true);
			}
			cursor = found;
			ticksAt = 0;
		}
		retryIn = 0;
		for (int moved = 0; moved < MAX_ADVANCE && reached(tape, spline, s, length, loop); moved++) {
			cursor = (cursor + 1) % n;
			ticksAt = 0;
		}
		ThrottleTape.Sample at = tape.sample(cursor);
		ticksAt++;
		boolean stop = at.throttle == 0 && at.holdTicks >= Math.max(1, minStopTicks);
		int target = at.throttle * at.orientation * (orientation < 0 ? -1 : 1);
		if (target != 0 && perThrottle > 0) {
			int most = brakingThrottle(roomToStop(tape, s, length, loop), perThrottle);
			if (Math.abs(target) > most) {
				target = Integer.signum(target) * most;
			}
		}
		return new Step(target, cursor, stop, false);
	}

	// Blocks to the next standing sample ahead on this track; infinite with none near.
	private double roomToStop(ThrottleTape tape, double s, double length, boolean loop) {
		ThrottleTape.Sample at = tape.sample(cursor);
		String track = at.resolvedSpline(tape.getSplineId());
		for (int k = 1; k <= BRAKE_LOOK_AHEAD && k < tape.size(); k++) {
			ThrottleTape.Sample next = tape.sample(cursor + k);
			if (!same(next, tape, track)) {
				break;
			}
			double ahead = ThrottleTape.along(s, next.s, length, loop) * at.sign;
			if (ahead > BRAKE_DISTANCE) {
				break;
			}
			if (next.throttle == 0) {
				return Math.max(0, ahead);
			}
		}
		return Double.POSITIVE_INFINITY;
	}

	/**
	 * The highest throttle that still stops within {@code room} blocks, coming down a point a
	 * tick; at least 1 while the stop is further than {@link #REACH}, so the train gets there.
	 */
	static int brakingThrottle(double room, double perThrottle) {
		if (Double.isInfinite(room)) {
			return Integer.MAX_VALUE;
		}
		int most = (int) Math.floor((Math.sqrt(1 + 8 * (room + BRAKE_SLACK) / perThrottle) - 1) / 2);
		return room > REACH ? Math.max(1, most) : most;
	}

	// Off the tape's track, or far from where the tape says the train is.
	private boolean lost(ThrottleTape tape, String spline, double s, double length, boolean loop) {
		ThrottleTape.Sample at = tape.sample(cursor);
		ThrottleTape.Sample next = tape.sample(cursor + 1);
		boolean onAt = same(at, tape, spline);
		boolean onNext = same(next, tape, spline);
		if (!onAt && !onNext) {
			return true;
		}
		ThrottleTape.Sample on = onAt ? at : next;
		double d = Math.abs(ThrottleTape.along(on.s, s, length, loop));
		if (onAt && onNext) {
			d = Math.min(d, Math.abs(ThrottleTape.along(next.s, s, length, loop)));
		}
		return d > RESYNC_DISTANCE;
	}

	private boolean reached(ThrottleTape tape, String spline, double s, double length, boolean loop) {
		ThrottleTape.Sample at = tape.sample(cursor);
		ThrottleTape.Sample next = tape.sample(cursor + 1);
		if (!same(next, tape, at.resolvedSpline(tape.getSplineId()))) {
			// The next sample is on another track: reached once the train is on it.
			return same(next, tape, spline);
		}
		// Both on one track, which the train is on: lost() checked that before.
		double d = ThrottleTape.along(at.s, next.s, length, loop);
		if (Math.abs(d) < STATIONARY || at.throttle == 0) {
			return ticksAt >= at.holdTicks;
		}
		double progress = ThrottleTape.along(at.s, s, length, loop) * Math.signum(d);
		return progress >= Math.abs(d) - REACH;
	}

	private static boolean same(ThrottleTape.Sample sample, ThrottleTape tape, String spline) {
		String a = sample.resolvedSpline(tape.getSplineId());
		return a != null && a.equalsIgnoreCase(spline);
	}
}
