package net.tfminecraft.vehicleframework.tracks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.json.simple.parser.JSONParser;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import net.tfminecraft.vehicleframework.database.ConsistData;
import net.tfminecraft.vehicleframework.database.VehiclePayloadCodec;
import net.tfminecraft.vehicleframework.database.VehicleRepository;
import net.tfminecraft.vehicleframework.database.VehicleSnapshot;
import net.tfminecraft.vehicleframework.loaders.VehicleLoader;
import net.tfminecraft.vehicleframework.vehicles.Vehicle;
import net.tfminecraft.vehicleframework.vehicles.handlers.container.Container;

/**
 * Reads and rewrites the saved row of a train car that is not loaded: its place, its
 * coupling and track position, its fuel, and the items in its containers. Everything else
 * in the row is written back as it was read.
 */
public final class StoredCars {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String[] CONSIST_KEYS = {"parent", "child", "splineId", "s", "travelSign", "orientation",
			"junctions", "junction", "diverge"};

	private StoredCars() {
	}

	/** A saved car's row with its payload parsed, ready to change and write back. */
	public static final class Row {
		private final VehicleSnapshot snapshot;
		private final JSONObject json;
		private String world;
		private double x;
		private double y;
		private double z;
		private float yaw;

		Row(VehicleSnapshot snapshot, JSONObject json) {
			this.snapshot = snapshot;
			this.json = json;
			world = snapshot.getWorld();
			x = snapshot.getX();
			y = snapshot.getY();
			z = snapshot.getZ();
			yaw = snapshot.getYaw();
		}

		public JSONObject json() {
			return json;
		}

		public String owner() {
			return snapshot.getOwner();
		}

		public String typeId() {
			return snapshot.getTypeId();
		}

		public void place(String world, double x, double y, double z, float yaw) {
			this.world = world;
			this.x = x;
			this.y = y;
			this.z = z;
			this.yaw = yaw;
		}

		VehicleSnapshot toSnapshot(long now) {
			TreeMap<String, Object> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
			sorted.putAll(json);
			return new VehicleSnapshot(snapshot.getUuid(), snapshot.getTypeId(), snapshot.getName(),
					snapshot.getOwner(), world, x, y, z, yaw, (int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4,
					GSON.toJson(sorted), snapshot.getSchemaVersion(), snapshot.getRevision(), false, now);
		}
	}

	/** The live (not destroyed) row of a car; empty if there is none or it cannot be read. */
	public static Optional<Row> read(VehicleRepository repository, String uuid) {
		Optional<VehicleSnapshot> found = repository.findLive(uuid);
		if (found.isEmpty()) {
			return Optional.empty();
		}
		try {
			if (new JSONParser().parse(found.get().getPayloadJson()) instanceof JSONObject json) {
				return Optional.of(new Row(found.get(), json));
			}
		} catch (Exception unreadable) {
			// A row that cannot be parsed cannot be changed either.
		}
		return Optional.empty();
	}

	public static boolean write(VehicleRepository repository, Row row) {
		return repository.saveLive(row.toSnapshot(System.currentTimeMillis()));
	}

	/** Replaces the coupling and track position, keeping the saved parent and child. */
	public static void putConsist(JSONObject json, ConsistData consist) {
		ConsistData saved = ConsistData.fromJson(json);
		for (String key : CONSIST_KEYS) {
			json.remove(key);
		}
		new ConsistData(saved.getParent(), saved.getChild(), consist.getSplineId(), consist.getS(),
				consist.getTravelSign(), null, null, consist.getOrientation(), consist.getJunctions()).put(json);
	}

	/** Sets the engine's fuel and throttle, whichever kind of engine the car has. */
	@SuppressWarnings("unchecked")
	public static void putEngine(JSONObject json, double fuel, int throttle) {
		if (!(json.get("components") instanceof JSONObject components)) {
			return;
		}
		for (String type : new String[] {"engine", "geared_engine"}) {
			if (components.get(type) instanceof JSONObject engine) {
				engine.put("fuel", fuel);
				engine.put("throttle", (long) throttle);
			}
		}
	}

	/** The car's containers as configured for its type, holding the saved items. */
	public static List<Container> containers(Row row) {
		List<Container> out = new ArrayList<>();
		Vehicle type = VehicleLoader.getByString(row.typeId());
		if (type == null || type.getContainerHandler() == null) {
			return out;
		}
		Map<String, JsonObject> saved = new java.util.HashMap<>();
		for (JsonObject container : VehiclePayloadCodec.loadContainers(row.json())) {
			if (container.has("id")) {
				saved.put(container.get("id").getAsString().toLowerCase(java.util.Locale.ROOT), container);
			}
		}
		for (Container template : type.getContainerHandler().getContainers().values()) {
			Container copy = Container.detached(template);
			JsonObject items = saved.get(copy.getId().toLowerCase(java.util.Locale.ROOT));
			copy.loadFromJson(items == null ? new JsonObject() : items);
			out.add(copy);
		}
		return out;
	}

	/** Writes the containers' items back into the row. */
	@SuppressWarnings("unchecked")
	public static void putContainers(Row row, List<Container> containers) {
		JSONObject section = row.json().get("containers") instanceof JSONObject existing ? existing : new JSONObject();
		for (Container container : containers) {
			section.put(container.getId(), JSONValue.parse(container.getAsJson().toString()));
		}
		row.json().put("containers", section);
	}
}
