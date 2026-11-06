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
 * other trains near its tracks are listed: other runs on paper where they have got to,
 * loaded trains and parked saved trains. A step that brings one of its cars too close to one
 * of them, and closer than it already was, is taken back, so it waits there until the way is
 * clear. It keeps {@code routes.paper-gap} blocks from another run on paper and stops just
 * short of a loaded or parked train. A train it already overlaps may still move away.
 *
 * <p>Distance is along the rails: on the same track, or through a switch between the two
 * tracks (each car's distance to the frog added together).
 */
final class PaperSpacing {
	/** A car of another train: on which track, where along it, how far it reaches and the gap kept. */
	record Car(String train, UUID track, double s, double reach, double gap) {
	}

	private static final double EPSILON = 1e-6;

	private final TrackRegistry registry;
	// Loaded and parked cars, by track; runs on paper are read as they move.
	private final Map<UUID, List<Car>> fixed = new HashMap<>();
	// The switches on each track, as stem or as branch.
	private final Map<UUID, List<TrackJunction>> switches = new HashMap<>();

	private PaperSpacing(TrackRegistry registry) {
		this.registry = registry;
		for (TrackJunction junction : registry.junctions()) {
			if (junction.branchSplineId != null) {
				switches.computeIfAbsent(junction.stemSplineId, track -> new ArrayList<>()).add(junction);
				switches.computeIfAbsent(junction.branchSplineId, track -> new ArrayList<>()).add(junction);
			}
		}
	}

	/** The loaded and parked trains as they are now. */
	static PaperSpacing now(TrackRegistry registry, Collection<ActiveVehicle> loaded, List<TrackedCar> parked,
			Collection<GhostTrain> runs) {
		PaperSpacing spacing = new PaperSpacing(registry);
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
				spacing.add(new Car(uuid, train.getSplineId(), train.getS(), train.trackReach(), 0));
			}
		}
		for (TrackedCar car : parked) {
			UUID track = uuid(car.splineId());
			if (track != null && !skip.contains(car.uuid().toLowerCase(Locale.ROOT))) {
				spacing.add(new Car(car.uuid(), track, car.s(), Cache.trainCollisionWarningParkedHalfLength, 0));
			}
		}
		return spacing;
	}

	/** The loaded and parked trains on the server now. */
	static PaperSpacing now(Collection<GhostTrain> runs) {
		VehicleRepository repository = VehicleFramework.getVehicleRepository();
		return now(VehicleFramework.getTrackRegistry(), VehicleFramework.getVehicleManager().get().values(),
				repository == null ? List.of() : repository.trackedCars(), runs);
	}

	/** A run that spawned or stopped during this tick stands where it was; the rest keep clear of it. */
	void stood(GhostTrain run) {
		for (int i = 0; i < run.spots.size() && i < run.reaches.length; i++) {
			GhostTrain.Spot spot = run.spots.get(i);
			add(new Car(run.loco(), spot.splineId(), spot.s(), run.reaches[i], 0));
		}
	}

	private void add(Car car) {
		fixed.computeIfAbsent(car.track(), track -> new ArrayList<>()).add(car);
	}

	/**
	 * The train the step from {@code before} to {@code after} runs into, or null when the way
	 * is clear.
	 */
	String blocker(GhostTrain ghost, List<GhostTrain.Spot> before, List<GhostTrain.Spot> after,
			Collection<GhostTrain> runs) {
		Set<UUID> tracks = new HashSet<>();
		for (GhostTrain.Spot spot : after) {
			tracks.add(spot.splineId());
			for (TrackJunction junction : switches.getOrDefault(spot.splineId(), List.of())) {
				tracks.add(junction.stemSplineId);
				tracks.add(junction.branchSplineId);
			}
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
					near.add(new Car(run.loco(), spot.splineId(), spot.s(), run.reaches[i], Cache.trainGhostGap));
				}
			}
		}
		for (Car car : near) {
			double now = room(ghost, after, car);
			if (now < 0 && now < room(ghost, before, car) - EPSILON) {
				return car.train();
			}
		}
		return null;
	}

	// The least room between the ghost's cars and this car beyond the gap; negative is too close.
	double room(GhostTrain ghost, List<GhostTrain.Spot> spots, Car car) {
		double least = Double.POSITIVE_INFINITY;
		for (int i = 0; i < spots.size() && i < ghost.reaches.length; i++) {
			double apart = apart(spots.get(i), car);
			least = Math.min(least, apart - ghost.reaches[i] - car.reach() - car.gap());
		}
		return least;
	}

	// Distance along the rails, on one track or through a switch between the two; infinite if neither.
	double apart(GhostTrain.Spot spot, Car car) {
		if (spot.splineId().equals(car.track())) {
			TrackSpline track = registry.get(car.track()).orElse(null);
			return TrackJunction.arcDistance(spot.s(), car.s(), track == null ? 0 : track.length(),
					track != null && track.isLoop());
		}
		double least = Double.POSITIVE_INFINITY;
		for (TrackJunction junction : switches.getOrDefault(spot.splineId(), List.of())) {
			if (spot.splineId().equals(junction.stemSplineId) && car.track().equals(junction.branchSplineId)) {
				least = Math.min(least, toFrog(junction, junction.stemSplineId, spot.s())
						+ toFrog(junction, junction.branchSplineId, car.s()));
			} else if (spot.splineId().equals(junction.branchSplineId) && car.track().equals(junction.stemSplineId)) {
				least = Math.min(least, toFrog(junction, junction.branchSplineId, spot.s())
						+ toFrog(junction, junction.stemSplineId, car.s()));
			}
		}
		return least;
	}

	private double toFrog(TrackJunction junction, UUID track, double s) {
		TrackSpline spline = registry.get(track).orElse(null);
		double length = spline == null ? 0 : spline.length();
		if (track.equals(junction.stemSplineId)) {
			return TrackJunction.arcDistance(s, junction.s, length, spline != null && spline.isLoop());
		}
		return Math.abs(junction.fromFrog(s, length));
	}

	private static UUID uuid(String id) {
		try {
			return id == null ? null : UUID.fromString(id);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
