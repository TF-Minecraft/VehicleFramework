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
		return new Step(target, cursor, stop, false);
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
