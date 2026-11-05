package net.tfminecraft.vehicleframework.tracks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

	public static final class DwellState {
		public int left;
		public Double atS;
	}

	public enum AppendResult {
		HELD, ADDED, CAPPED
	}

	private final String splineId;
	private final List<Sample> samples;
	// Playback asks the tape every tick. Each spline and direction's samples, sorted along the
	// track, are built on first use and dropped when a sample is added.
	private final Map<String, Index[]> indexes = new HashMap<>();

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

	public boolean takesJunction(UUID junctionId) {
		if (junctionId == null) {
			return false;
		}
		String want = junctionId.toString();
		for (Sample sample : samples) {
			if (want.equalsIgnoreCase(sample.junctionId)) {
				return true;
			}
		}
		return false;
	}

	public List<Sample> getSamples() {
		return List.copyOf(samples);
	}

	public int lookup(double s, int sign) {
		return lookup(s, sign, splineId);
	}

	public int lookup(double s, int sign, UUID currentSpline) {
		return lookup(s, sign, currentSpline == null ? splineId : currentSpline.toString());
	}

	public int lookup(double s, int sign, String currentSpline) {
		return index(currentSpline, sign).lookup(s);
	}

	static int lookup(List<Sample> samples, double s, int sign) {
		if (samples == null || samples.isEmpty()) {
			return 0;
		}
		int want = sign < 0 ? -1 : 1;
		List<Sample> matched = new ArrayList<>();
		for (Sample sample : samples) {
			if (sample.sign == want) {
				matched.add(sample);
			}
		}
		if (matched.isEmpty()) {
			return 0;
		}
		matched.sort(Comparator.comparingDouble(a -> a.s));
		if (s <= matched.get(0).s) {
			return matched.get(0).throttle * matched.get(0).orientation;
		}
		Sample last = matched.get(matched.size() - 1);
		if (s >= last.s) {
			return last.throttle * last.orientation;
		}
		for (int i = 0; i < matched.size() - 1; i++) {
			Sample a = matched.get(i);
			Sample b = matched.get(i + 1);
			if (s >= a.s && s <= b.s) {
				double t = (s - a.s) / (b.s - a.s);
				return (int) Math.round(a.throttle * a.orientation + t * (b.throttle * b.orientation - a.throttle * a.orientation));
			}
		}
		return 0;
	}

	public int holdAt(double s, int sign) {
		return holdAt(s, sign, splineId);
	}

	public int holdAt(double s, int sign, UUID currentSpline) {
		return holdAt(s, sign, currentSpline == null ? splineId : currentSpline.toString());
	}

	/** The hold of the recorded sample nearest {@code s}, the first recorded on a tie, if under {@link #HOLD_S} away. */
	public int holdAt(double s, int sign, String currentSpline) {
		return index(currentSpline, sign).holdAt(s);
	}

	public int targetWithDwell(double s, int sign, DwellState dwell) {
		return targetWithDwell(s, sign, dwell, splineId);
	}

	public int targetWithDwell(double s, int sign, DwellState dwell, UUID currentSpline) {
		return targetWithDwell(s, sign, dwell, currentSpline == null ? splineId : currentSpline.toString());
	}

	/** Converts each recorded sample from its body frame, then into the current body's frame. */
	public int targetWithDwell(double s, int sign, DwellState dwell, UUID currentSpline, int orientation) {
		return targetWithDwell(s, sign, dwell, currentSpline) * (orientation < 0 ? -1 : 1);
	}

	public int targetWithDwell(double s, int sign, DwellState dwell, String currentSpline) {
		int target = lookup(s, sign, currentSpline);
		int hold = holdAt(s, sign, currentSpline);
		if (dwell == null) {
			return target;
		}
		if (target == 0 && hold > 1) {
			if (dwell.atS == null || Math.abs(s - dwell.atS) >= HOLD_S) {
				dwell.left = hold;
				dwell.atS = s;
			}
			if (dwell.left > 0) {
				dwell.left--;
				return 0;
			}
		} else if (dwell.atS != null && Math.abs(s - dwell.atS) >= HOLD_S) {
			dwell.left = 0;
			dwell.atS = null;
		}
		return target;
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
		indexes.clear();
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

	private Index index(String currentSpline, int sign) {
		String want = wanted(currentSpline);
		Index[] bySign = indexes.computeIfAbsent(want, key -> new Index[2]);
		int slot = sign < 0 ? 0 : 1;
		if (bySign[slot] == null) {
			bySign[slot] = new Index(samplesFor(want), sign < 0 ? -1 : 1);
		}
		return bySign[slot];
	}

	private String wanted(String currentSpline) {
		String want = blankToNull(currentSpline);
		return want == null ? splineId : want;
	}

	/**
	 * One spline and direction's samples, sorted along the track the way {@link #lookup(List, double, int)}
	 * sorts them, so both give the same answers. A sample at NaN sorts last and is never between two others.
	 */
	static final class Index {
		private final Sample[] sorted;
		// Each sorted sample's place in recording order.
		private final int[] recorded;
		// Sorted samples before the first NaN.
		private final int numbered;

		Index(List<Sample> samples, int want) {
			List<Sample> matched = new ArrayList<>();
			for (Sample sample : samples) {
				if (sample.sign == want) {
					matched.add(sample);
				}
			}
			Integer[] order = new Integer[matched.size()];
			for (int i = 0; i < order.length; i++) {
				order[i] = i;
			}
			// A stable sort, as List.sort is: equal positions keep recording order.
			java.util.Arrays.sort(order, Comparator.comparingDouble(i -> matched.get(i).s));
			sorted = new Sample[order.length];
			recorded = new int[order.length];
			int count = 0;
			for (int i = 0; i < order.length; i++) {
				sorted[i] = matched.get(order[i]);
				recorded[i] = order[i];
				if (!Double.isNaN(sorted[i].s)) {
					count++;
				}
			}
			numbered = count;
		}

		int lookup(double s) {
			if (sorted.length == 0) {
				return 0;
			}
			Sample first = sorted[0];
			if (s <= first.s) {
				return first.throttle * first.orientation;
			}
			Sample last = sorted[sorted.length - 1];
			if (s >= last.s) {
				return last.throttle * last.orientation;
			}
			// The first pair with a.s <= s <= b.s: b is the first sample from the second on at
			// or past s, as "s <= sample.s" only turns true once along the numbered samples.
			int lo = 1;
			int hi = numbered;
			while (lo < hi) {
				int mid = (lo + hi) >>> 1;
				if (s <= sorted[mid].s) {
					hi = mid;
				} else {
					lo = mid + 1;
				}
			}
			if (lo >= numbered) {
				return 0;
			}
			Sample a = sorted[lo - 1];
			Sample b = sorted[lo];
			double t = (s - a.s) / (b.s - a.s);
			return (int) Math.round(a.throttle * a.orientation + t * (b.throttle * b.orientation - a.throttle * a.orientation));
		}

		int holdAt(double s) {
			if (!(Math.abs(s) < Double.POSITIVE_INFINITY)) {
				// Nothing is a finite distance from an infinite or NaN position.
				return 1;
			}
			// sample.s - s only grows along the sorted samples, so those under HOLD_S away
			// sit together: from the first more than HOLD_S behind s up to HOLD_S ahead.
			int lo = 0;
			int hi = numbered;
			while (lo < hi) {
				int mid = (lo + hi) >>> 1;
				if (sorted[mid].s - s > -HOLD_S) {
					hi = mid;
				} else {
					lo = mid + 1;
				}
			}
			Sample best = null;
			double bestD = HOLD_S;
			int bestRecorded = Integer.MAX_VALUE;
			for (int i = lo; i < numbered && sorted[i].s - s < HOLD_S; i++) {
				double d = Math.abs(sorted[i].s - s);
				if (d < bestD || (d == bestD && recorded[i] < bestRecorded)) {
					bestD = d;
					best = sorted[i];
					bestRecorded = recorded[i];
				}
			}
			return best == null ? 1 : best.holdTicks;
		}
	}

	private List<Sample> samplesFor(String want) {
		List<Sample> out = new ArrayList<>();
		for (Sample sample : samples) {
			if (sameId(sample.resolvedSpline(splineId), want)) {
				out.add(sample);
			}
		}
		return out;
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
