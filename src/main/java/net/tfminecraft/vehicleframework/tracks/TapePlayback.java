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
	static final int BRAKE_LOOK_AHEAD = 160;
	/** Stops further than this (or than the stopping distance, if longer) are not braked for yet. */
	static final double BRAKE_DISTANCE = 64;
	/** Stops further along the tape than this are never braked for yet. */
	static final double REST_LIMIT = 256;
	/** Consecutive samples further apart than this on another track are across its loop seam. */
	static final double SEAM_GAP = 4;
	/** What a change of track between two samples counts towards the room to stop. */
	static final double STEP_LIMIT = 2;
	/** Half a block's slack, so a recorded slowing down is not cut short. */
	static final double BRAKE_SLACK = 0.5;
	/** Ticks between looks for the train's place when it is off the tape's tracks. */
	static final int RETRY_TICKS = 20;

	private int cursor = -1;
	private int ticksAt;
	private int retryIn;
	// The room to stop beyond the sample after the cursor, for this cursor and tape.
	private int restCursor = -1;
	private ThrottleTape restTape;
	private double rest;

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
		restCursor = -1;
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
			restCursor = -1;
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
			double window = Math.max(BRAKE_DISTANCE, perThrottle * Math.abs(target) * (Math.abs(target) + 1) / 2 + 4);
			int most = brakingThrottle(roomToStop(tape, spline, s, length, loop, window), perThrottle);
			if (Math.abs(target) > most) {
				target = Integer.signum(target) * most;
			}
		}
		return new Step(target, cursor, stop, false);
	}

	/**
	 * Blocks along the tape to its next standing sample, over switches and loop seams;
	 * infinite with none within {@code window}. The part beyond the next sample only changes
	 * with the cursor, so it is looked up once per sample, not every tick.
	 */
	private double roomToStop(ThrottleTape tape, String spline, double s, double length, boolean loop,
			double window) {
		ThrottleTape.Sample at = tape.sample(cursor);
		ThrottleTape.Sample first = tape.sample(cursor + 1);
		double room = STEP_LIMIT;
		if (same(first, tape, spline)) {
			// The way the tape goes from here; the sample's own sign is from before a reversal.
			double between = ThrottleTape.along(at.s, first.s, length, loop);
			int way = between > 1e-6 ? 1 : between < -1e-6 ? -1 : at.sign;
			room = Math.max(0, ThrottleTape.along(s, first.s, length, loop) * way);
		}
		if (restCursor != cursor || restTape != tape) {
			restCursor = cursor;
			restTape = tape;
			rest = rest(tape, first, spline, length, loop);
		}
		return room + rest <= window ? room + rest : Double.POSITIVE_INFINITY;
	}

	// From the sample after the cursor on to the next standing sample.
	private double rest(ThrottleTape tape, ThrottleTape.Sample first, String spline, double length, boolean loop) {
		double room = 0;
		ThrottleTape.Sample previous = first;
		for (int k = 1; k <= BRAKE_LOOK_AHEAD && k < tape.size() && room <= REST_LIMIT; k++) {
			if (previous.throttle == 0) {
				return room;
			}
			ThrottleTape.Sample next = tape.sample(cursor + k + 1);
			room += step(tape, previous, next, spline, length, loop);
			previous = next;
		}
		return Double.POSITIVE_INFINITY;
	}

	// One step along the tape. Positions on two tracks cannot be compared: a change of track
	// counts as a short step. On the train's own track, the short way round a loop.
	private static double step(ThrottleTape tape, ThrottleTape.Sample from, ThrottleTape.Sample to, String spline,
			double length, boolean loop) {
		String track = from.resolvedSpline(tape.getSplineId());
		if (!same(to, tape, track)) {
			return STEP_LIMIT;
		}
		if (track.equalsIgnoreCase(spline)) {
			return Math.abs(ThrottleTape.along(from.s, to.s, length, loop));
		}
		// Another track's length is not known here: a gap longer than a moving step is its seam.
		double gap = Math.abs(to.s - from.s);
		return gap > SEAM_GAP ? STEP_LIMIT : gap;
	}

	/**
	 * The highest throttle that still stops within {@code room} blocks, coming down a point a
	 * tick; at least 1, so a train short of a stop it cannot pass yet still gets there.
	 */
	static int brakingThrottle(double room, double perThrottle) {
		if (Double.isInfinite(room)) {
			return Integer.MAX_VALUE;
		}
		return Math.max(1, (int) Math.floor((Math.sqrt(1 + 8 * (room + BRAKE_SLACK) / perThrottle) - 1) / 2));
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
		if (next.throttle == 0) {
			// A stop is reached on coming level with it, travelling the way the train goes, even
			// one the recording ran a little past (a loop's lap ends a tick's travel beyond it).
			return ThrottleTape.along(next.s, s, length, loop) * at.sign >= -REACH;
		}
		double progress = ThrottleTape.along(at.s, s, length, loop) * Math.signum(d);
		return progress >= Math.abs(d) - REACH;
	}

	private static boolean same(ThrottleTape.Sample sample, ThrottleTape tape, String spline) {
		String a = sample.resolvedSpline(tape.getSplineId());
		return a != null && a.equalsIgnoreCase(spline);
	}
}
