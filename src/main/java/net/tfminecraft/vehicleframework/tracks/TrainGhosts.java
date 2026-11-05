package net.tfminecraft.vehicleframework.tracks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import net.tfminecraft.vehicleframework.VFLogger;
import net.tfminecraft.vehicleframework.VehicleFramework;
import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.database.ConsistData;
import net.tfminecraft.vehicleframework.database.TrackedCar;
import net.tfminecraft.vehicleframework.database.VehiclePayloadCodec;
import net.tfminecraft.vehicleframework.database.VehicleRepository;
import net.tfminecraft.vehicleframework.database.VehicleSnapshot;
import net.tfminecraft.vehicleframework.enums.Component;
import net.tfminecraft.vehicleframework.managers.VehicleManager;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.component.Engine;
import net.tfminecraft.vehicleframework.vehicles.component.GearedEngine;
import net.tfminecraft.vehicleframework.vehicles.component.fuel.FuelTank;
import net.tfminecraft.vehicleframework.vehicles.handlers.TrainHandler;
import net.tfminecraft.vehicleframework.vehicles.handlers.container.Container;

/**
 * Tape-driven trains nobody is near keep running "on paper". When such a train's chunk
 * unloads, or it reaches a chunk that is not loaded, its cars are saved and removed as for
 * any unload, and a {@link GhostTrain} moves it along its timetable once a second. When a
 * player comes near, its rows are moved to where it has got to and it spawns there.
 *
 * <p>Its cars never spawn from their saved rows while on paper, so a car is never both
 * a running ghost and an entity.
 */
public final class TrainGhosts {
	/** The locomotive's payload key for a run on paper; present only while it runs unloaded. */
	public static final String KEY = "ghostRun";
	static final int TICKS = 20;

	private static final Map<String, GhostTrain> ghosts = new LinkedHashMap<>();
	private static final Map<String, GhostTrain> byCar = new HashMap<>();
	private static final Set<String> requested = new LinkedHashSet<>();
	// Cars spawned from a run on paper: their saved track position was just written, so trust it.
	private static final Set<String> trusted = new HashSet<>();

	enum Spawn {
		SPAWNED, BLOCKED, NOT_READY
	}

	private TrainGhosts() {
	}

	public static boolean isGhost(String uuid) {
		return uuid != null && byCar.containsKey(norm(uuid));
	}

	/** Whether this run is still on paper (not spawned or ended). */
	public static boolean running(GhostTrain ghost) {
		return ghost != null && ghosts.get(ghost.loco()) == ghost;
	}

	public static int count() {
		return ghosts.size();
	}

	public static List<GhostTrain> all() {
		return List.copyOf(ghosts.values());
	}

	/** Whether this car was just spawned from a run on paper; asking forgets it. */
	public static boolean consumeTrusted(String uuid) {
		return uuid != null && trusted.remove(norm(uuid));
	}

	/** Asks for this train to go on paper after the vehicle tick, which must not remove vehicles. */
	public static void request(ActiveVehicle loco) {
		if (loco != null && loco.getUUID() != null) {
			requested.add(norm(loco.getUUID()));
		}
	}

	/** Converts the trains that asked during this tick. */
	public static void flush(VehicleManager vehicles) {
		if (requested.isEmpty()) {
			return;
		}
		List<String> ids = new ArrayList<>(requested);
		requested.clear();
		for (String id : ids) {
			ActiveVehicle loco = vehicles.getByUUID(id);
			if (loco != null) {
				tryConvert(loco, "edge");
			}
		}
	}

	/**
	 * Puts the train this car belongs to on paper if it qualifies: a tape installed and
	 * playing, fuel, no one aboard, every car loaded and on track. False leaves it alone.
	 */
	public static boolean tryConvert(ActiveVehicle any, String why) {
		ActiveVehicle loco = root(any);
		if (!eligible(loco)) {
			return false;
		}
		TrainHandler.Layout layout = loco.getTrainHandler().layout();
		GhostTrain ghost = capture(loco, layout);
		if (ghost == null) {
			return false;
		}
		VehicleManager vehicles = VehicleFramework.getVehicleManager();
		for (ActiveVehicle car : layout.cars()) {
			if (!vehicles.unload(car, "going on paper")) {
				// Cars already saved respawn from their rows as usual; this one stays loaded.
				VFLogger.log("Could not save train car " + car.getUUID() + "; its train does not run unloaded");
				return false;
			}
		}
		register(ghost);
		save(ghost, false);
		RecorderLog.append("PAPER_START why=" + why + " loco=" + ghost.loco() + " cars=" + ghost.cars.size()
				+ " cursor=" + ghost.clock.cursor() + " fuel=" + ghost.fuel);
		return true;
	}

	static boolean eligible(ActiveVehicle loco) {
		if (!Cache.trainRunUnloaded || loco == null || !loco.isTrain() || loco.hasParent() || loco.isDestroyed()
				|| loco.getUUID() == null || isGhost(loco.getUUID()) || loco.getEntity() == null) {
			return false;
		}
		TrainHandler train = loco.getTrainHandler();
		FuelTank tank = tank(loco);
		if (!train.isBound() || !train.hasInstalledTape() || train.isRecording() || train.hasMissingCars()
				|| (tank != null && tank.useFuel() && tank.getCurrent() <= 0)) {
			return false;
		}
		World world = loco.getEntity().getWorld();
		for (ActiveVehicle car : train.layout().cars()) {
			if (car.isDestroyed() || car.getEntity() == null || car.getEntity().getWorld() != world
					|| car.getSeatHandler().hasPassengers() || !car.getTrainHandler().isBound()) {
				return false;
			}
		}
		return true;
	}

	static GhostTrain capture(ActiveVehicle loco, TrainHandler.Layout layout) {
		TrainHandler train = loco.getTrainHandler();
		ThrottleTape tape = ThrottleTape.fromJson(train.getInstalledTape().toJson());
		int[] at = train.getTapeClock();
		int cursor = at[0];
		int ticks = at[1];
		TrackRegistry registry = VehicleFramework.getTrackRegistry();
		TrackSpline spline = registry.get(train.getSplineId()).orElse(null);
		if (spline == null) {
			return null;
		}
		// A train driven off its route by hand would otherwise jump back onto it.
		if (cursor < 0 || cursor >= tape.size() || !near(TapeTimetable.place(tape,
				new TapeTimetable.Clock(cursor, ticks), registry), spline, train.getS())) {
			cursor = tape.nearestIndex(spline.getId().toString(), train.getS(), train.getTravelSign(), cursor,
					TapePlayback.RESYNC_DISTANCE, spline.length(), spline.isLoop());
			ticks = 0;
		}
		if (cursor < 0) {
			return null;
		}
		List<String> cars = new ArrayList<>();
		List<String> owners = new ArrayList<>();
		for (ActiveVehicle car : layout.cars()) {
			cars.add(norm(car.getUUID()));
			owners.add(car.getOwnerData() == null ? "none" : car.getOwnerData().getOwner());
		}
		FuelTank tank = tank(loco);
		boolean useFuel = tank != null && tank.useFuel();
		String fuelPath = null;
		int perItem = 0;
		int reserve = 0;
		if (useFuel && layout.cars().size() > 1 && train.acceptsFuelCar(layout.cars().get(1))
				&& layout.cars().get(1).hasContainers()) {
			fuelPath = tank.getInput().getItem();
			perItem = tank.getInput().getAmount();
			for (Container container : layout.cars().get(1).getContainerHandler().getContainers().values()) {
				reserve += container.countMatching(fuelPath);
			}
		}
		return new GhostTrain(loco.getEntity().getWorld().getName(), cars, owners, layout.offsets(), layout.reaches(),
				tape, new TapeTimetable.Clock(cursor, ticks), layout.routes(), useFuel ? tank.getCurrent() : 0,
				useFuel, useFuel ? tank.getRate() : 0, loco.isLocomotive(), useFuel ? tank.getCapacity() : 0,
				fuelPath, perItem, reserve);
	}

	private static boolean near(TapeTimetable.Place place, TrackSpline spline, double s) {
		return spline.getId().toString().equalsIgnoreCase(place.splineId())
				&& Math.abs(ThrottleTape.along(place.s(), s, spline.length(), spline.isLoop()))
						<= TapePlayback.RESYNC_DISTANCE;
	}

	/** One second for every train on paper. */
	public static void tick() {
		if (ghosts.isEmpty()) {
			return;
		}
		TrackRegistry registry = VehicleFramework.getTrackRegistry();
		for (GhostTrain ghost : new ArrayList<>(ghosts.values())) {
			try {
				tickOne(ghost, registry);
			} catch (RuntimeException e) {
				VFLogger.log("Train " + ghost.loco() + " running unloaded failed a step: " + e);
			}
		}
	}

	static void tickOne(GhostTrain ghost, TrackRegistry registry) {
		List<GhostTrain.Spot> spots = spots(ghost, registry);
		if (!spots.isEmpty() && nearPlayer(ghost, spots)) {
			Spawn spawned = materialize(ghost, spots, registry);
			if (spawned == Spawn.SPAWNED) {
				return;
			}
			if (spawned == Spawn.BLOCKED) {
				// Someone can see where it would appear and something is there: wait for it to clear.
				RecorderLog.append("PAPER_BLOCKED loco=" + ghost.loco());
				return;
			}
		}
		List<Integer> stops = ghost.run(TICKS, registry);
		if (!stops.isEmpty()) {
			spots(ghost, registry);
			for (int stop : stops) {
				ghost.stopSerial++;
				CargoDocks.servePaper(ghost, stop);
			}
		}
		if (++ghost.sinceSave >= Cache.trainGhostSaveSeconds) {
			ghost.sinceSave = 0;
			save(ghost, false);
		}
	}

	// Where the cars are now; the last known places if a track they were on is gone.
	private static List<GhostTrain.Spot> spots(GhostTrain ghost, TrackRegistry registry) {
		List<GhostTrain.Spot> placed = ghost.place(registry);
		return placed == null ? ghost.spots : placed;
	}

	static boolean nearPlayer(GhostTrain ghost, List<GhostTrain.Spot> spots) {
		World world = Bukkit.getWorld(ghost.world);
		if (world == null) {
			return false;
		}
		double range = Cache.despawnDistance * 0.8;
		for (Player player : world.getPlayers()) {
			Location at = player.getLocation();
			for (GhostTrain.Spot spot : spots) {
				double dx = at.getX() - spot.pose().x;
				double dy = at.getY() - spot.pose().y;
				double dz = at.getZ() - spot.pose().z;
				if (dx * dx + dy * dy + dz * dz <= range) {
					return true;
				}
			}
		}
		return false;
	}

	static Spawn materialize(GhostTrain ghost, List<GhostTrain.Spot> spots, TrackRegistry registry) {
		World world = Bukkit.getWorld(ghost.world);
		if (world == null || !areaLoaded(world, spots)) {
			return Spawn.NOT_READY;
		}
		if (blocked(world, ghost, spots, registry)) {
			return Spawn.BLOCKED;
		}
		if (!save(ghost, true)) {
			return Spawn.NOT_READY;
		}
		unregister(ghost);
		if (spotsCurrent(spots, registry)) {
			trusted.addAll(ghost.cars);
		}
		int spawned = 0;
		for (String uuid : ghost.cars) {
			if (VehicleFramework.getVehicleManager().getSpawnManager().spawnNow(uuid)) {
				spawned++;
			}
		}
		RecorderLog.append("PAPER_END loco=" + ghost.loco() + " spawned=" + spawned + "/" + ghost.cars.size()
				+ " cursor=" + ghost.clock.cursor() + " fuel=" + ghost.fuel);
		return Spawn.SPAWNED;
	}

	// Saved places on tracks that are gone are checked against the track when the cars load.
	private static boolean spotsCurrent(List<GhostTrain.Spot> spots, TrackRegistry registry) {
		for (GhostTrain.Spot spot : spots) {
			if (registry.get(spot.splineId()).isEmpty()) {
				return false;
			}
		}
		return true;
	}

	// Every car's chunk, and two chunks all round the locomotive so it does not reach the edge at once.
	static boolean areaLoaded(World world, List<GhostTrain.Spot> spots) {
		for (GhostTrain.Spot spot : spots) {
			if (!world.isChunkLoaded((int) Math.floor(spot.pose().x) >> 4, (int) Math.floor(spot.pose().z) >> 4)) {
				return false;
			}
		}
		int cx = (int) Math.floor(spots.get(0).pose().x) >> 4;
		int cz = (int) Math.floor(spots.get(0).pose().z) >> 4;
		for (int x = cx - 2; x <= cx + 2; x++) {
			for (int z = cz - 2; z <= cz + 2; z++) {
				if (!world.isChunkLoaded(x, z)) {
					return false;
				}
			}
		}
		return true;
	}

	// Blocks in a car's space, or another train (loaded, on paper, or parked) on the same rails.
	static boolean blocked(World world, GhostTrain ghost, List<GhostTrain.Spot> spots, TrackRegistry registry) {
		for (int i = 0; i < spots.size(); i++) {
			GhostTrain.Spot spot = spots.get(i);
			TrackSpline track = registry.get(spot.splineId()).orElse(null);
			if (TrainBlockCollision.occupied(world, track, spot.s(), ghost.reaches[i])
					!= TrainBlockCollision.Clearance.CLEAR) {
				return true;
			}
		}
		Set<String> skip = new HashSet<>(ghost.cars);
		for (ActiveVehicle vehicle : VehicleFramework.getVehicleManager().get().values()) {
			if (vehicle.getUUID() == null || !vehicle.isTrain()) {
				continue;
			}
			String uuid = norm(vehicle.getUUID());
			skip.add(uuid);
			TrainHandler train = vehicle.getTrainHandler();
			if (train.isBound() && overlaps(ghost, spots, registry, train.getSplineId(), train.getS(),
					train.trackReach())) {
				return true;
			}
		}
		// Trains on paper pass through each other; their saved rows are not where they are.
		for (GhostTrain other : ghosts.values()) {
			skip.addAll(other.cars);
		}
		VehicleRepository repository = VehicleFramework.getVehicleRepository();
		for (TrackedCar parked : repository == null ? List.<TrackedCar>of() : repository.trackedCars()) {
			UUID spline = skip.contains(norm(parked.uuid())) ? null : uuid(parked.splineId());
			if (spline != null
					&& overlaps(ghost, spots, registry, spline, parked.s(), Cache.trainCollisionWarningParkedHalfLength)) {
				return true;
			}
		}
		return false;
	}

	private static UUID uuid(String id) {
		try {
			return id == null ? null : UUID.fromString(id);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static boolean overlaps(GhostTrain ghost, List<GhostTrain.Spot> spots, TrackRegistry registry,
			UUID spline, double s, double reach) {
		for (int i = 0; i < spots.size(); i++) {
			GhostTrain.Spot spot = spots.get(i);
			if (!spot.splineId().equals(spline)) {
				continue;
			}
			TrackSpline track = registry.get(spline).orElse(null);
			double gap = TrackJunction.arcDistance(spot.s(), s, track == null ? 0 : track.length(),
					track != null && track.isLoop());
			if (gap < ghost.reaches[i] + reach) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Writes every car's row where the run has got to. {@code end} also clears the run, ready
	 * to spawn. False if nothing could be written; a locomotive row that is gone ends the run.
	 */
	static boolean save(GhostTrain ghost, boolean end) {
		VehicleRepository repository = VehicleFramework.getVehicleRepository();
		if (repository == null) {
			return false;
		}
		List<GhostTrain.Spot> spots = spots(ghost, VehicleFramework.getTrackRegistry());
		boolean[] lost = {false};
		try {
			repository.runInTransaction(() -> {
				flushFuel(repository, ghost);
				for (int i = 0; i < ghost.cars.size(); i++) {
					Optional<StoredCars.Row> found = StoredCars.read(repository, ghost.cars.get(i));
					if (found.isEmpty()) {
						lost[0] |= i == 0;
						continue;
					}
					StoredCars.Row row = found.get();
					if (i < spots.size()) {
						place(ghost, row, spots.get(i));
					}
					if (i == 0) {
						writeRun(ghost, row.json(), end);
					}
					StoredCars.write(repository, row);
				}
			});
		} catch (RuntimeException e) {
			VFLogger.log("Could not save train " + ghost.loco() + " running unloaded: " + e.getMessage());
			return false;
		}
		if (lost[0]) {
			unregister(ghost);
			VFLogger.log("Train " + ghost.loco() + " stopped running unloaded: its locomotive was removed");
			return false;
		}
		return true;
	}

	private static void place(GhostTrain ghost, StoredCars.Row row, GhostTrain.Spot spot) {
		Map<String, Boolean> routes = new LinkedHashMap<>();
		ghost.routes.forEach((id, diverge) -> routes.put(id.toString(), diverge));
		StoredCars.putConsist(row.json(), new ConsistData(null, null, spot.splineId().toString(), spot.s(),
				spot.sign(), null, null, spot.orientation(), routes));
		float yaw = TrackSplineMotion.worldHeading(null, spot.pose(), 1).getYaw();
		row.place(ghost.world, spot.pose().x, spot.pose().y + Cache.trackVehicleYOffset, spot.pose().z, yaw);
	}

	@SuppressWarnings("unchecked")
	private static void writeRun(GhostTrain ghost, JSONObject json, boolean end) {
		StoredCars.putEngine(json, ghost.fuel, 0);
		VehiclePayloadCodec.putTapeClock(json, new int[] {ghost.clock.cursor(), ghost.clock.ticksAt()});
		if (end) {
			json.remove(KEY);
		} else {
			json.put(KEY, ghost.toJson());
		}
	}

	// Takes the fuel items the engine used on paper out of the fuel car's saved containers.
	static void flushFuel(VehicleRepository repository, GhostTrain ghost) {
		if (ghost.consumed <= 0 || ghost.cars.size() < 2) {
			return;
		}
		Optional<StoredCars.Row> found = StoredCars.read(repository, ghost.cars.get(1));
		if (found.isEmpty()) {
			return;
		}
		List<Container> containers = StoredCars.containers(found.get());
		int left = ghost.consumed;
		for (Container container : containers) {
			while (left > 0 && container.takeOneMatching(ghost.fuelPath) != null) {
				left--;
			}
		}
		StoredCars.putContainers(found.get(), containers);
		StoredCars.write(repository, found.get());
		ghost.consumed = 0;
	}

	/** Saves every train on paper; on shutdown, so each resumes from where it was. */
	public static void saveAll() {
		for (GhostTrain ghost : new ArrayList<>(ghosts.values())) {
			save(ghost, false);
		}
	}

	/** Picks up the trains that were on paper when the server stopped, where they were. */
	public static void restore() {
		ghosts.clear();
		byCar.clear();
		requested.clear();
		trusted.clear();
		VehicleRepository repository = VehicleFramework.getVehicleRepository();
		if (repository == null) {
			return;
		}
		for (VehicleSnapshot row : repository.listAllLive()) {
			String payload = row.getPayloadJson();
			if (payload == null || !payload.contains("\"" + KEY + "\"")) {
				continue;
			}
			try {
				JSONObject json = (JSONObject) new JSONParser().parse(payload);
				ThrottleTape tape = json.get("throttleTape") instanceof JSONObject saved ? ThrottleTape.fromJson(saved)
						: null;
				GhostTrain ghost = GhostTrain.fromJson((JSONObject) json.get(KEY), tape);
				if (ghost != null && ghost.loco().equalsIgnoreCase(row.getUuid())) {
					// Spread the saves of resumed runs over the save interval.
					ghost.sinceSave = Math.floorMod(ghost.loco().hashCode(), Math.max(1, Cache.trainGhostSaveSeconds));
					register(ghost);
				}
			} catch (Exception e) {
				VFLogger.log("Could not resume train " + row.getUuid() + " running unloaded: " + e);
			}
		}
		if (!ghosts.isEmpty()) {
			VFLogger.info("Resumed " + ghosts.size() + " train(s) running unloaded");
		}
	}

	static void register(GhostTrain ghost) {
		ghosts.put(ghost.loco(), ghost);
		for (String car : ghost.cars) {
			byCar.put(car, ghost);
		}
	}

	static void unregister(GhostTrain ghost) {
		ghosts.remove(ghost.loco());
		for (String car : ghost.cars) {
			byCar.remove(car, ghost);
		}
	}

	static FuelTank tank(ActiveVehicle vehicle) {
		if (vehicle.hasComponent(Component.ENGINE)) {
			return ((Engine) vehicle.getComponent(Component.ENGINE)).getFuelTank();
		}
		if (vehicle.hasComponent(Component.GEARED_ENGINE)) {
			return ((GearedEngine) vehicle.getComponent(Component.GEARED_ENGINE)).getFuelTank();
		}
		return null;
	}

	private static ActiveVehicle root(ActiveVehicle car) {
		ActiveVehicle loco = car;
		while (loco != null && loco.hasParent()) {
			loco = loco.getParent();
		}
		return loco;
	}

	static String norm(String uuid) {
		return uuid.toLowerCase(Locale.ROOT);
	}
}
