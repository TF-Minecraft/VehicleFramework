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

	/** A stretch of one track, from {@code lo} to {@code hi} along it. */
	record Window(UUID track, double lo, double hi) {
		boolean holds(UUID on, double s) {
			return track.equals(on) && s >= lo && s <= hi;
		}
	}

	private static final double EPSILON = 1e-6;

	private final TrackRegistry registry;
	// Loaded and parked cars, by track; runs on paper are read as they move.
	private final Map<UUID, List<Car>> fixed = new HashMap<>();
	// The same, ordered along each track, made when first needed.
	private final Map<UUID, Car[]> ordered = new HashMap<>();
	// The longest reach of any car checked, so only those near enough to touch are looked at.
	private double widest;
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
			for (double reach : run.reaches) {
				spacing.widest = Math.max(spacing.widest, reach);
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
		// A saved place past the end of a loop (the track was shortened since) is that far round it.
		TrackSpline spline = registry.get(car.track()).orElse(null);
		if (spline != null && spline.isLoop() && spline.length() > 0 && (car.s() < 0 || car.s() > spline.length())) {
			double length = spline.length();
			car = new Car(car.train(), car.track(), ((car.s() % length) + length) % length, car.reach(), car.gap());
		}
		fixed.computeIfAbsent(car.track(), track -> new ArrayList<>()).add(car);
		ordered.remove(car.track());
		widest = Math.max(widest, car.reach());
	}

	/**
	 * The train the step from {@code before} to {@code after} runs into, or null when the way
	 * is clear.
	 */
	String blocker(GhostTrain ghost, List<GhostTrain.Spot> before, List<GhostTrain.Spot> after,
			Collection<GhostTrain> runs) {
		List<Window> windows = windows(ghost, after);
		for (Window window : windows) {
			for (Car car : within(window)) {
				if (blocks(ghost, before, after, car)) {
					return car.train();
				}
			}
		}
		for (GhostTrain run : runs) {
			if (run == ghost) {
				continue;
			}
			for (int i = 0; i < run.spots.size() && i < run.reaches.length; i++) {
				GhostTrain.Spot spot = run.spots.get(i);
				if (inside(windows, spot.splineId(), spot.s())) {
					Car car = new Car(run.loco(), spot.splineId(), spot.s(), run.reaches[i], Cache.trainGhostGap);
					if (blocks(ghost, before, after, car)) {
						return car.train();
					}
				}
			}
		}
		return null;
	}

	private boolean blocks(GhostTrain ghost, List<GhostTrain.Spot> before, List<GhostTrain.Spot> after, Car car) {
		double now = room(ghost, after, car);
		return now < 0 && now < room(ghost, before, car) - EPSILON;
	}

	private static boolean inside(List<Window> windows, UUID track, double s) {
		for (Window window : windows) {
			if (window.holds(track, s)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The stretches of track where a car could be close enough to touch one of the ghost's cars:
	 * around each car on its own track, and past a switch near it on the track beyond.
	 */
	List<Window> windows(GhostTrain ghost, List<GhostTrain.Spot> spots) {
		double own = 0;
		for (double reach : ghost.reaches) {
			own = Math.max(own, reach);
		}
		double range = own + widest + Math.max(0, Cache.trainGhostGap) + 1;
		List<Window> out = new ArrayList<>();
		for (GhostTrain.Spot spot : spots) {
			span(out, spot.splineId(), spot.s(), range);
			for (TrackJunction junction : switches.getOrDefault(spot.splineId(), List.of())) {
				boolean onStem = spot.splineId().equals(junction.stemSplineId);
				UUID beyond = onStem ? junction.branchSplineId : junction.stemSplineId;
				double left = range - toFrog(junction, spot.splineId(), spot.s());
				if (left >= 0 && !beyond.equals(spot.splineId())) {
					span(out, beyond, frogAt(junction, beyond), left);
				}
			}
		}
		return out;
	}

	// The frog's place along this track of the switch.
	private double frogAt(TrackJunction junction, UUID track) {
		if (track.equals(junction.stemSplineId)) {
			return junction.s;
		}
		TrackSpline spline = registry.get(track).orElse(null);
		return junction.branchFrogS(spline == null ? 0 : spline.length());
	}

	// From s - range to s + range along the track; on a loop, split where it wraps.
	private void span(List<Window> out, UUID track, double s, double range) {
		TrackSpline spline = registry.get(track).orElse(null);
		double length = spline == null ? 0 : spline.length();
		double lo = s - range, hi = s + range;
		if (spline == null || !spline.isLoop() || (lo >= 0 && hi <= length)) {
			out.add(new Window(track, lo, hi));
		} else if (2 * range >= length) {
			out.add(new Window(track, 0, length));
		} else if (lo < 0) {
			out.add(new Window(track, 0, hi));
			out.add(new Window(track, lo + length, length));
		} else {
			out.add(new Window(track, lo, length));
			out.add(new Window(track, 0, hi - length));
		}
	}

	// The loaded and parked cars in a window, found by halving along the ordered track.
	private List<Car> within(Window window) {
		List<Car> cars = fixed.get(window.track());
		if (cars == null) {
			return List.of();
		}
		Car[] sorted = ordered.computeIfAbsent(window.track(), track -> cars.stream()
				.sorted(java.util.Comparator.comparingDouble(Car::s)).toArray(Car[]::new));
		int low = 0, high = sorted.length;
		while (low < high) {
			int mid = (low + high) >>> 1;
			if (sorted[mid].s() < window.lo()) {
				low = mid + 1;
			} else {
				high = mid;
			}
		}
		List<Car> out = new ArrayList<>();
		for (int i = low; i < sorted.length && sorted[i].s() <= window.hi(); i++) {
			out.add(sorted[i]);
		}
		return out;
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
