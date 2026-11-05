package net.tfminecraft.vehicleframework.tracks;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

public final class ThrottleTape {
	public static final int MAX_SAMPLES = 2000;
	public static final double HOLD_S = 1.0;

	public static final class Sample {
		public final double s;
		public final int sign;
		public final int throttle;
		public final int orientation;
		public int holdTicks;
		public final String splineId;
		public final String junctionId;

		public Sample(double s, int sign, int throttle) {
			this(s, sign, throttle, 1, null, null);
		}

		public Sample(double s, int sign, int throttle, int holdTicks) {
			this(s, sign, throttle, holdTicks, null, null);
		}

		public Sample(double s, int sign, int throttle, int holdTicks, String splineId, String junctionId) {
			this(s, sign, throttle, holdTicks, splineId, junctionId, 1);
		}

		public Sample(double s, int sign, int throttle, int holdTicks, String splineId, String junctionId, int orientation) {
			this.orientation = orientation < 0 ? -1 : 1;
			this.s = s;
			this.sign = sign < 0 ? -1 : 1;
			this.throttle = throttle;
			this.holdTicks = Math.max(1, holdTicks);
			this.splineId = blankToNull(splineId);
			this.junctionId = blankToNull(junctionId);
		}

		public String resolvedSpline(String origin) {
			return splineId == null ? origin : splineId;
		}
	}

	public enum AppendResult {
		HELD, ADDED, CAPPED
	}

	private final String splineId;
	private final List<Sample> samples;
	// Derived from the samples; rebuilt when a sample is added.
	private int[] changes;

	public ThrottleTape(String splineId) {
		this(splineId, List.of());
	}

	public ThrottleTape(String splineId, List<Sample> samples) {
		this.splineId = splineId == null ? "" : splineId;
		this.samples = new ArrayList<>(samples == null ? List.of() : samples);
	}

	public String getSplineId() {
		return splineId;
	}

	public boolean isEmpty() {
		return samples.isEmpty();
	}

	public boolean matchesSpline(UUID id) {
		return id != null && id.toString().equalsIgnoreCase(splineId);
	}

	public List<Sample> getSamples() {
		return List.copyOf(samples);
	}

	public int size() {
		return samples.size();
	}

	/** The sample at {@code index}, wrapping around the end of the tape. */
	public Sample sample(int index) {
		int n = samples.size();
		return samples.get(((index % n) + n) % n);
	}

	public Sample last() {
		return samples.isEmpty() ? null : samples.get(samples.size() - 1);
	}

	/**
	 * One run of the tape, in ticks. A sample is taken every tick and a held sample counts
	 * each tick it was held, so the tape is also a timetable.
	 */
	public long totalTicks() {
		long total = 0;
		for (Sample sample : samples) {
			total += sample.holdTicks;
		}
		return total;
	}

	/**
	 * The first sample after {@code index}, going round the tape once, that is on another
	 * track than the sample at {@code index}; -1 if the whole tape stays on that track.
	 */
	public int nextSplineChange(int index) {
		int n = samples.size();
		if (changes == null || changes.length != n) {
			changes = splineChanges();
		}
		return n == 0 ? -1 : changes[((index % n) + n) % n];
	}

	private int[] splineChanges() {
		int n = samples.size();
		int[] next = new int[n];
		java.util.Arrays.fill(next, -1);
		int pending = -1;
		// Two passes round the ring: the first finds changes past the end of the tape.
		for (int k = 2 * n - 1; k >= 0; k--) {
			int i = k % n;
			int after = (i + 1) % n;
			if (!sameId(samples.get(i).resolvedSpline(splineId), samples.get(after).resolvedSpline(splineId))) {
				pending = after;
			}
			if (k < n) {
				next[i] = pending;
			}
		}
		return next;
	}

	/**
	 * The sample a train at {@code s} on {@code spline} should resume from: on that track,
	 * within {@code reach} blocks, travelling with {@code sign} if any does, then the first
	 * one at or after {@code prefer} in tape order, then the nearest. -1 if there is none.
	 */
	public int nearestIndex(String spline, double s, int sign, int prefer, double reach, double length, boolean loop) {
		int n = samples.size();
		int want = sign < 0 ? -1 : 1;
		int best = -1;
		double bestScore = Double.POSITIVE_INFINITY;
		for (int i = 0; i < n; i++) {
			Sample sample = samples.get(i);
			double d = Math.abs(along(sample.s, s, length, loop));
			if (d > reach || !sameId(sample.resolvedSpline(splineId), blankToNull(spline))) {
				continue;
			}
			int order = prefer < 0 ? 0 : Math.floorMod(i - prefer, n);
			double score = (sample.sign == want ? 0 : n) + order + d / (reach + 1);
			if (score < bestScore) {
				bestScore = score;
				best = i;
			}
		}
		return best;
	}

	/** Signed distance along a track from {@code from} to {@code to}, the short way round a loop. */
	public static double along(double from, double to, double length, boolean loop) {
		double d = to - from;
		if (loop && length > 1e-9) {
			d -= length * Math.rint(d / length);
		}
		return d;
	}

	public AppendResult tryAppend(double s, int sign, int throttle) {
		return tryAppend(s, sign, throttle, splineId, null);
	}

	public AppendResult tryAppend(double s, int sign, int throttle, String sampleSpline, String junctionId) {
		return tryAppend(s, sign, throttle, sampleSpline, junctionId, 1);
	}

	public AppendResult tryAppend(double s, int sign, int throttle, String sampleSpline, String junctionId, int orientation) {
		int nsign = sign < 0 ? -1 : 1;
		String spline = blankToNull(sampleSpline);
		if (spline == null) {
			spline = blankToNull(this.splineId);
		}
		String junction = blankToNull(junctionId);
		if (!samples.isEmpty()) {
			Sample last = samples.get(samples.size() - 1);
			String lastSpline = blankToNull(last.resolvedSpline(this.splineId));
			boolean splineChanged = !sameId(lastSpline, spline);
			boolean junctionChanged = !sameId(last.junctionId, junction);
			boolean throttleChanged = last.throttle != throttle || last.sign != nsign || last.orientation != orientation;
			boolean far = Math.abs(s - last.s) >= HOLD_S;
			if (!splineChanged && !junctionChanged && !throttleChanged && !far) {
				last.holdTicks++;
				return AppendResult.HELD;
			}
		}
		if (samples.size() >= MAX_SAMPLES) {
			return AppendResult.CAPPED;
		}
		samples.add(new Sample(s, nsign, throttle, 1, spline, junction, orientation));
		changes = null;
		if (samples.size() >= MAX_SAMPLES) {
			return AppendResult.CAPPED;
		}
		return AppendResult.ADDED;
	}

	@SuppressWarnings("unchecked")
	public JSONObject toJson() {
		JSONObject root = new JSONObject();
		root.put("splineId", splineId);
		JSONArray arr = new JSONArray();
		for (Sample sample : samples) {
			JSONObject o = new JSONObject();
			o.put("s", sample.s);
			o.put("sign", (long) sample.sign);
			o.put("throttle", (long) sample.throttle);
			if (sample.orientation < 0) { o.put("orientation", -1L); }
			o.put("hold", (long) sample.holdTicks);
			if (sample.splineId != null && !sample.splineId.equalsIgnoreCase(splineId)) {
				o.put("splineId", sample.splineId);
			}
			if (sample.junctionId != null) {
				o.put("junction", sample.junctionId);
			}
			arr.add(o);
		}
		root.put("samples", arr);
		return root;
	}

	public static ThrottleTape fromJson(JSONObject root) {
		if (root == null) {
			return null;
		}
		Object idRaw = root.get("splineId");
		if (idRaw == null) {
			return null;
		}
		String splineId = String.valueOf(idRaw);
		if (splineId.isBlank()) {
			return null;
		}
		List<Sample> loaded = new ArrayList<>();
		Object arrRaw = root.get("samples");
		if (arrRaw instanceof JSONArray arr) {
			for (Object raw : arr) {
				if (!(raw instanceof JSONObject o)) {
					continue;
				}
				int hold = o.get("hold") == null ? 1 : (int) asDouble(o.get("hold"));
				loaded.add(new Sample(
						asDouble(o.get("s")),
						(int) asDouble(o.get("sign")),
						(int) asDouble(o.get("throttle")),
						hold,
						stringOrNull(o.get("splineId")),
						stringOrNull(o.get("junction")),
						o.get("orientation") == null ? 1 : (int) asDouble(o.get("orientation"))));
			}
		}
		return new ThrottleTape(splineId, loaded);
	}

	private static boolean sameId(String a, String b) {
		if (a == null && b == null) {
			return true;
		}
		if (a == null || b == null) {
			return false;
		}
		return a.equalsIgnoreCase(b);
	}

	private static String blankToNull(String value) {
		if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) {
			return null;
		}
		return value;
	}

	private static String stringOrNull(Object raw) {
		if (raw == null) {
			return null;
		}
		return blankToNull(String.valueOf(raw));
	}

	private static double asDouble(Object value) {
		if (value instanceof Number n) {
			return n.doubleValue();
		}
		if (value == null) {
			return 0;
		}
		try {
			return Double.parseDouble(String.valueOf(value));
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
