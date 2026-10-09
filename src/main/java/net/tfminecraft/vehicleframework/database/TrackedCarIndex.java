package net.tfminecraft.vehicleframework.database;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/**
 * In-memory track positions of saved train cars, so moving trains can see cars parked in
 * unloaded chunks without asking SQLite each check.
 */
final class TrackedCarIndex {
	private final Map<String, TrackedCar> byUuid = new HashMap<>();

	void replace(Iterable<VehicleSnapshot> rows) {
		byUuid.clear();
		for (VehicleSnapshot row : rows) {
			put(row);
		}
	}

	void put(VehicleSnapshot snapshot) {
		String key = normalizeUuid(snapshot.getUuid());
		if (key == null) {
			return;
		}
		TrackedCar car = snapshot.isDeleted() ? null : parse(key, snapshot);
		if (car == null) {
			byUuid.remove(key);
		} else {
			byUuid.put(key, car);
		}
	}

	void remove(String uuid) {
		String key = normalizeUuid(uuid);
		if (key != null) {
			byUuid.remove(key);
		}
	}

	List<TrackedCar> all() {
		return List.copyOf(byUuid.values());
	}

	static TrackedCar parse(String uuid, VehicleSnapshot snapshot) {
		String payload = snapshot.getPayloadJson();
		// Only train cars on track save a spline; skip parsing everything else.
		if (payload == null || !payload.contains("\"splineId\"")) {
			return null;
		}
		try {
			if (new JSONParser().parse(payload) instanceof JSONObject json) {
				ConsistData consist = ConsistData.fromJson(json);
				if (consist.getSplineId() != null && consist.getS() != null) {
					return new TrackedCar(uuid, consist.getSplineId(), consist.getS(),
							normalizeUuid(consist.getParent()), snapshot.getWorld(),
							snapshot.getX(), snapshot.getY(), snapshot.getZ());
				}
			}
		} catch (Exception ignored) {
			// An unreadable row cannot be loaded either, so it holds no train.
		}
		return null;
	}

	static String normalizeUuid(String uuid) {
		if (uuid == null || uuid.isBlank()) {
			return null;
		}
		return uuid.trim().toLowerCase(Locale.ROOT);
	}
}
