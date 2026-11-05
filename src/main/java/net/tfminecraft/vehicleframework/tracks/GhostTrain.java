package net.tfminecraft.vehicleframework.tracks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.vehicles.handlers.train.LocomotiveOverdrive;

/**
 * A tape-driven train running "on paper": its cars are saved and their entities gone, and
 * it moves along its tape's timetable once a second without loading a chunk or reading a
 * block. Each car keeps its distance behind the locomotive along the track, so the train
 * stays together; when a player comes near it is spawned where it has got to.
 */
public final class GhostTrain {
	/** Where a car is: its track position and the world pose it faces. */
	public record Spot(UUID splineId, double s, int orientation, int sign, TrackPose pose) {
	}

	final String world;
	final List<String> cars;
	final List<String> owners;
	final double[] offsets;
	final double[] reaches;
	final ThrottleTape tape;
	TapeTimetable.Clock clock;
	final Map<UUID, Boolean> routes = new LinkedHashMap<>();
	double fuel;
	final boolean useFuel;
	final double burn;
	final boolean locomotive;
	final double capacity;
	// The fuel car's matching items: what it held, and how many the engine has used since saved.
	final String fuelPath;
	final int fuelPerItem;
	int reserve;
	int consumed;
	int stopSerial;
	// Items the docks at the current stop may still move.
	int dockBudget;
	int sinceSave;
	List<Spot> spots = List.of();

	GhostTrain(String world, List<String> cars, List<String> owners, double[] offsets, double[] reaches,
			ThrottleTape tape, TapeTimetable.Clock clock, Map<UUID, Boolean> routes, double fuel, boolean useFuel,
			double burn, boolean locomotive, double capacity, String fuelPath, int fuelPerItem, int reserve) {
		this.world = world;
		this.cars = new ArrayList<>(cars);
		this.owners = new ArrayList<>(owners);
		this.offsets = offsets.clone();
		this.reaches = reaches.clone();
		this.tape = tape;
		this.clock = clock;
		this.routes.putAll(routes);
		this.fuel = fuel;
		this.useFuel = useFuel;
		this.burn = burn;
		this.locomotive = locomotive;
		this.capacity = capacity;
		this.fuelPath = fuelPath;
		this.fuelPerItem = fuelPerItem;
		this.reserve = reserve;
	}

	public String loco() {
		return cars.get(0);
	}

	public List<String> cars() {
		return List.copyOf(cars);
	}

	/** Out of fuel, with none left in the fuel car: it stands where it is. */
	public boolean stalled() {
		return useFuel && fuel <= 0 && reserve <= 0;
	}

	/**
	 * Runs {@code ticks} of the timetable: takes fuel from the fuel car as the engine would,
	 * burns it while under power, and follows the switches the tape crossed. Returns the
	 * recorded stops it arrived at.
	 */
	public List<Integer> run(int ticks, TrackRegistry registry) {
		refuel();
		if (stalled()) {
			return List.of();
		}
		TapeTimetable.Advance advance = TapeTimetable.advance(tape, clock, ticks, Cache.trainDockMinStopTicks);
		int from = clock.cursor();
		int to = advance.clock().cursor();
		for (int i = from; i != to; i = (i + 1) % tape.size()) {
			crossed(registry, tape.sample(i), tape.sample(i + 1));
		}
		clock = advance.clock();
		if (useFuel && advance.maxThrottle() != 0) {
			double multiplier = locomotive ? LocomotiveOverdrive.fuelMultiplier(advance.maxThrottle()) : 1.0;
			fuel = Math.max(0, fuel - burn * multiplier);
		}
		return advance.stops();
	}

	// The engine takes one fuel item from the fuel car each second the tank has room.
	private void refuel() {
		if (useFuel && fuel < capacity && reserve > 0 && fuelPerItem > 0) {
			fuel = Math.min(capacity, fuel + fuelPerItem);
			reserve--;
			consumed++;
		}
	}

	// Moving between a stem and its branch: the cars behind follow the locomotive's way.
	private void crossed(TrackRegistry registry, ThrottleTape.Sample a, ThrottleTape.Sample b) {
		String from = a.resolvedSpline(tape.getSplineId());
		String to = b.resolvedSpline(tape.getSplineId());
		if (registry == null || from.equalsIgnoreCase(to)) {
			return;
		}
		for (TrackJunction junction : registry.junctions()) {
			String stem = junction.stemSplineId.toString();
			String branch = String.valueOf(junction.branchSplineId);
			if ((stem.equalsIgnoreCase(from) && branch.equalsIgnoreCase(to))
					|| (branch.equalsIgnoreCase(from) && stem.equalsIgnoreCase(to))) {
				routes.put(junction.id, true);
			}
		}
	}

	/**
	 * Where every car is now, from the locomotive's place in the timetable back along the
	 * track. Switches no car stands on any more are forgotten. Null if a track is gone.
	 */
	public List<Spot> place(TrackRegistry registry) {
		TapeTimetable.Place lead = TapeTimetable.place(tape, clock, registry);
		TrackSpline leadTrack = TapeTimetable.track(registry, lead.splineId());
		if (leadTrack == null) {
			return null;
		}
		TrainRoute route = new TrainRoute(registry, routes);
		TrainRoute.Position at = new TrainRoute.Position(leadTrack.getId(), lead.s(), lead.orientation());
		int bodyTravel = lead.sign() * lead.orientation();
		Map<UUID, Boolean> used = new HashMap<>();
		List<Spot> out = new ArrayList<>();
		for (double offset : offsets) {
			TrainRoute.Walk walk = route.walk(at, -offset);
			used.putAll(walk.junctions());
			TrainRoute.Position car = walk.position();
			// A walk only ends on tracks the registry has.
			TrackSpline track = registry.get(car.splineId()).orElseThrow();
			TrackPose pose = TrainRoute.facing(track.sampleAt(car.s()), car.orientation());
			out.add(new Spot(car.splineId(), car.s(), car.orientation(), bodyTravel * car.orientation(), pose));
		}
		routes.keySet().retainAll(used.keySet());
		spots = out;
		return out;
	}

	/** The run's state, saved in the locomotive's row so it carries on after a restart. */
	@SuppressWarnings("unchecked")
	public JSONObject toJson() {
		JSONObject json = new JSONObject();
		json.put("world", world);
		JSONArray carIds = new JSONArray();
		carIds.addAll(cars);
		json.put("cars", carIds);
		JSONArray ownerIds = new JSONArray();
		ownerIds.addAll(owners);
		json.put("owners", ownerIds);
		JSONArray offsetList = new JSONArray();
		JSONArray reachList = new JSONArray();
		for (int i = 0; i < offsets.length; i++) {
			offsetList.add(offsets[i]);
			reachList.add(reaches[i]);
		}
		json.put("offsets", offsetList);
		json.put("reaches", reachList);
		json.put("cursor", (long) clock.cursor());
		json.put("ticks", (long) clock.ticksAt());
		JSONObject switches = new JSONObject();
		routes.forEach((id, diverge) -> switches.put(id.toString(), diverge));
		json.put("routes", switches);
		json.put("fuel", fuel);
		json.put("useFuel", useFuel);
		json.put("burn", burn);
		json.put("locomotive", locomotive);
		json.put("capacity", capacity);
		if (fuelPath != null) {
			json.put("fuelPath", fuelPath);
		}
		json.put("fuelPerItem", (long) fuelPerItem);
		json.put("reserve", (long) reserve);
		return json;
	}

	/** Reads a saved run; null if it is incomplete. */
	public static GhostTrain fromJson(JSONObject json, ThrottleTape tape) {
		if (json == null || tape == null || tape.isEmpty() || !(json.get("cars") instanceof JSONArray carIds)
				|| carIds.isEmpty() || !(json.get("offsets") instanceof JSONArray offsetList)
				|| offsetList.size() != carIds.size() || json.get("world") == null) {
			return null;
		}
		List<String> cars = new ArrayList<>();
		List<String> owners = new ArrayList<>();
		JSONArray ownerIds = json.get("owners") instanceof JSONArray list ? list : new JSONArray();
		JSONArray reachList = json.get("reaches") instanceof JSONArray list ? list : new JSONArray();
		double[] offsets = new double[carIds.size()];
		double[] reaches = new double[carIds.size()];
		for (int i = 0; i < carIds.size(); i++) {
			cars.add(String.valueOf(carIds.get(i)));
			owners.add(i < ownerIds.size() ? String.valueOf(ownerIds.get(i)) : "none");
			offsets[i] = number(offsetList.get(i), 0);
			reaches[i] = i < reachList.size() ? number(reachList.get(i), 1) : 1;
		}
		Map<UUID, Boolean> routes = new LinkedHashMap<>();
		if (json.get("routes") instanceof JSONObject switches) {
			for (Object key : switches.keySet()) {
				try {
					routes.put(UUID.fromString(String.valueOf(key)), Boolean.TRUE.equals(switches.get(key)));
				} catch (IllegalArgumentException ignored) {
					// A switch id that cannot be read is one the cars no longer need.
				}
			}
		}
		TapeTimetable.Clock clock = new TapeTimetable.Clock((int) number(json.get("cursor"), 0),
				(int) number(json.get("ticks"), 0));
		return new GhostTrain(String.valueOf(json.get("world")), cars, owners, offsets, reaches, tape, clock, routes,
				number(json.get("fuel"), 0), Boolean.TRUE.equals(json.get("useFuel")), number(json.get("burn"), 0),
				Boolean.TRUE.equals(json.get("locomotive")), number(json.get("capacity"), 0),
				json.get("fuelPath") == null ? null : String.valueOf(json.get("fuelPath")),
				(int) number(json.get("fuelPerItem"), 0), (int) number(json.get("reserve"), 0));
	}

	private static double number(Object raw, double fallback) {
		return raw instanceof Number n ? n.doubleValue() : fallback;
	}
}
