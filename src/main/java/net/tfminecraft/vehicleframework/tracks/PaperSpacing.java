package net.tfminecraft.vehicleframework.tracks;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.tfminecraft.vehicleframework.VehicleFramework;
import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.database.TrackedCar;
import net.tfminecraft.vehicleframework.database.VehicleRepository;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.handlers.TrainHandler;

/**
 * Keeps a train running on paper from running into another train. Before it moves, the
 * other trains on its tracks are listed: other runs on paper where they have got to, loaded
 * trains and parked saved trains. A step that brings one of its cars closer than the gap to
 * one of them, and closer than it already was, is taken back, so it waits there until the
 * way is clear. A train it already overlaps (as when two met before this check) may still
 * move away.
 */
final class PaperSpacing {
	/** A car of another train: on which track, where along it and how far it reaches. */
	record Car(String train, UUID track, double s, double reach) {
	}

	private static final double EPSILON = 1e-6;

	// Loaded and parked cars, by track; runs on paper are read as they move.
	private final Map<UUID, List<Car>> fixed = new HashMap<>();

	private PaperSpacing() {
	}

	/** The loaded and parked trains as they are now. */
	static PaperSpacing now(Collection<ActiveVehicle> loaded, List<TrackedCar> parked, Collection<GhostTrain> runs) {
		PaperSpacing spacing = new PaperSpacing();
		Set<String> skip = new HashSet<>();
		for (GhostTrain run : runs) {
			for (String car : run.cars) {
				skip.add(car.toLowerCase(Locale.ROOT));
			}
		}
		for (ActiveVehicle vehicle : loaded) {
			if (vehicle.getUUID() == null || !vehicle.isTrain()) {
				continue;
			}
			String uuid = vehicle.getUUID().toLowerCase(Locale.ROOT);
			skip.add(uuid);
			TrainHandler train = vehicle.getTrainHandler();
			if (train.isBound() && train.getSplineId() != null) {
				spacing.add(new Car(uuid, train.getSplineId(), train.getS(), train.trackReach()));
			}
		}
		for (TrackedCar car : parked) {
			UUID track = uuid(car.splineId());
			if (track != null && !skip.contains(car.uuid().toLowerCase(Locale.ROOT))) {
				spacing.add(new Car(car.uuid(), track, car.s(), Cache.trainCollisionWarningParkedHalfLength));
			}
		}
		return spacing;
	}

	/** The loaded and parked trains on the server now. */
	static PaperSpacing now(Collection<GhostTrain> runs) {
		VehicleRepository repository = VehicleFramework.getVehicleRepository();
		return now(VehicleFramework.getVehicleManager().get().values(),
				repository == null ? List.of() : repository.trackedCars(), runs);
	}

	private void add(Car car) {
		fixed.computeIfAbsent(car.track(), track -> new ArrayList<>()).add(car);
	}

	/**
	 * The train the step from {@code before} to {@code after} runs into, or null when the way
	 * is clear.
	 */
	String blocker(GhostTrain ghost, List<GhostTrain.Spot> before, List<GhostTrain.Spot> after,
			Collection<GhostTrain> runs, TrackRegistry registry) {
		Set<UUID> tracks = new HashSet<>();
		for (GhostTrain.Spot spot : after) {
			tracks.add(spot.splineId());
		}
		List<Car> near = new ArrayList<>();
		for (UUID track : tracks) {
			near.addAll(fixed.getOrDefault(track, List.of()));
		}
		for (GhostTrain run : runs) {
			if (run == ghost) {
				continue;
			}
			for (int i = 0; i < run.spots.size() && i < run.reaches.length; i++) {
				GhostTrain.Spot spot = run.spots.get(i);
				if (tracks.contains(spot.splineId())) {
					near.add(new Car(run.loco(), spot.splineId(), spot.s(), run.reaches[i]));
				}
			}
		}
		for (Car car : near) {
			double now = room(ghost, after, car, registry);
			if (now < 0 && now < room(ghost, before, car, registry) - EPSILON) {
				return car.train();
			}
		}
		return null;
	}

	// The least room between the ghost's cars and this car beyond the gap; negative is too close.
	static double room(GhostTrain ghost, List<GhostTrain.Spot> spots, Car car, TrackRegistry registry) {
		TrackSpline track = registry.get(car.track()).orElse(null);
		double least = Double.POSITIVE_INFINITY;
		for (int i = 0; i < spots.size() && i < ghost.reaches.length; i++) {
			GhostTrain.Spot spot = spots.get(i);
			if (!spot.splineId().equals(car.track())) {
				continue;
			}
			double gap = TrackJunction.arcDistance(spot.s(), car.s(), track == null ? 0 : track.length(),
					track != null && track.isLoop());
			least = Math.min(least, gap - ghost.reaches[i] - car.reach() - Cache.trainGhostGap);
		}
		return least;
	}

	private static UUID uuid(String id) {
		try {
			return id == null ? null : UUID.fromString(id);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
