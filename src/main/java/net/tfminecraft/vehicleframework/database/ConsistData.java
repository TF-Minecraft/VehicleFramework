package net.tfminecraft.vehicleframework.database;

import java.util.LinkedHashMap;
import java.util.Map;

import org.json.simple.JSONObject;

public final class ConsistData {
	private final String parent;
	private final String child;
	private final String splineId;
	private final Double s;
	private final Integer travelSign;
	private final String junctionId;
	private final Boolean diverge;
	private final int orientation;
	private final Map<String, Boolean> junctions;

	public ConsistData(String parent, String child, String splineId, Double s) {
		this(parent, child, splineId, s, null, null, null);
	}

	public ConsistData(String parent, String child, String splineId, Double s, Integer travelSign) {
		this(parent, child, splineId, s, travelSign, null, null);
	}

	public ConsistData(
			String parent,
			String child,
			String splineId,
			Double s,
			Integer travelSign,
			String junctionId,
			Boolean diverge) {
		this(parent, child, splineId, s, travelSign, junctionId, diverge, 1, Map.of());
	}

	public ConsistData(String parent, String child, String splineId, Double s, Integer travelSign,
			String junctionId, Boolean diverge, int orientation, Map<String, Boolean> junctions) {
		this.parent = blankToNull(parent);
		this.child = blankToNull(child);
		this.splineId = blankToNull(splineId);
		this.s = this.splineId == null ? null : s;
		this.travelSign = this.splineId == null ? null : normalizeSign(travelSign);
		this.junctionId = this.splineId == null ? null : blankToNull(junctionId);
		this.diverge = this.junctionId == null ? null : diverge;
		this.orientation = normalizeSign(orientation);
		Map<String, Boolean> routes = new LinkedHashMap<>();
		if (this.junctionId != null) {
			routes.put(this.junctionId, Boolean.TRUE.equals(diverge));
		}
		if (this.splineId != null && junctions != null) {
			routes.putAll(junctions);
		}
		this.junctions = Map.copyOf(routes);
	}

	public static ConsistData unbound() {
		return new ConsistData(null, null, null, null);
	}

	public static ConsistData fromJson(JSONObject json) {
		if (json == null) {
			return unbound();
		}
		return new ConsistData(
				stringOrNull(json, "parent"),
				stringOrNull(json, "child"),
				stringOrNull(json, "splineId"),
				numberOrNull(json, "s"),
				intOrNull(json, "travelSign"),
				stringOrNull(json, "junction"),
				boolOrNull(json, "diverge"),
				normalizeSign(intOrNull(json, "orientation")),
				readJunctions(json));
	}

	@SuppressWarnings("unchecked")
	public void put(JSONObject json) {
		if (json == null) {
			return;
		}
		if (parent != null) {
			json.put("parent", parent);
		}
		if (child != null) {
			json.put("child", child);
		}
		if (splineId != null) {
			json.put("splineId", splineId);
			if (s != null) {
				json.put("s", s);
			}
			json.put("travelSign", (long) (travelSign == null ? 1 : travelSign));
			if (orientation < 0) {
				json.put("orientation", -1L);
			}
			if (!junctions.isEmpty()) {
				JSONObject routes = new JSONObject();
				routes.putAll(junctions);
				json.put("junctions", routes);
			}
			if (junctionId != null) {
				json.put("junction", junctionId);
				if (Boolean.TRUE.equals(diverge)) {
					json.put("diverge", true);
				}
			}
		}
	}

	public boolean isUnbound() {
		return parent == null && child == null && splineId == null;
	}

	public String getParent() {
		return parent;
	}

	public String getChild() {
		return child;
	}

	public String getSplineId() {
		return splineId;
	}

	public Double getS() {
		return s;
	}

	public int getTravelSign() {
		return travelSign == null ? 1 : travelSign;
	}

	public String getJunctionId() {
		return junctionId;
	}

	public boolean isDiverge() {
		return Boolean.TRUE.equals(diverge);
	}

	public int getOrientation() {
		return orientation;
	}

	public Map<String, Boolean> getJunctions() {
		return junctions;
	}

	private static Map<String, Boolean> readJunctions(JSONObject json) {
		Map<String, Boolean> routes = new LinkedHashMap<>();
		if (json.get("junctions") instanceof JSONObject raw) {
			for (Object key : raw.keySet()) {
				if (key instanceof String id && raw.get(key) instanceof Boolean choice) {
					routes.put(id, choice);
				}
			}
		}
		return routes;
	}

	private static Integer intOrNull(JSONObject json, String key) {
		Double n = numberOrNull(json, key);
		if (n == null) {
			return null;
		}
		return n.intValue();
	}

	private static Boolean boolOrNull(JSONObject json, String key) {
		if (!json.containsKey(key) || json.get(key) == null) {
			return null;
		}
		Object raw = json.get(key);
		if (raw instanceof Boolean b) {
			return b;
		}
		return Boolean.parseBoolean(String.valueOf(raw));
	}

	private static int normalizeSign(Integer travelSign) {
		if (travelSign != null && travelSign < 0) {
			return -1;
		}
		return 1;
	}

	private static String stringOrNull(JSONObject json, String key) {
		if (!json.containsKey(key) || json.get(key) == null) {
			return null;
		}
		String value = String.valueOf(json.get(key));
		return blankToNull(value);
	}

	private static Double numberOrNull(JSONObject json, String key) {
		if (!json.containsKey(key) || json.get(key) == null) {
			return null;
		}
		Object raw = json.get(key);
		if (raw instanceof Number number) {
			return number.doubleValue();
		}
		try {
			return Double.parseDouble(String.valueOf(raw));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static String blankToNull(String value) {
		if (value == null || value.isBlank() || value.equals("null")) {
			return null;
		}
		return value;
	}
}
