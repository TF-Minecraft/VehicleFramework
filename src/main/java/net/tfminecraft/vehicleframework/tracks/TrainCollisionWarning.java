package net.tfminecraft.vehicleframework.tracks;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import net.tfminecraft.vehicleframework.VehicleFramework;
import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.database.TrackedCar;
import net.tfminecraft.vehicleframework.database.VehicleRepository;
import net.tfminecraft.vehicleframework.enums.Component;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.component.VehicleComponent;
import net.tfminecraft.vehicleframework.vehicles.component.propulsion.Throttle;
import net.tfminecraft.vehicleframework.vehicles.handlers.train.LocomotiveOverdrive;

/**
 * Warns the riders of trains that will collide. The first warning flashes a title; a
 * boss bar then counts down with an alarm until the danger has passed.
 */
public final class TrainCollisionWarning {
	// A warning that lapses for less than this stays up, so a forecast near the limit does not flicker.
	static final int CLEAR_TICKS = 40;
	// The title flashes at most this often for each player.
	static final int TITLE_TICKS = 200;
	// How close a parked car's saved world position must be to its track to trust it.
	private static final double ON_TRACK = 2.0;
	// The fastest a held key or a throttle tape moves the throttle.
	private static final double THROTTLE_POINTS_PER_TICK = 1;
	// How long a car whose track could not be found waits before looking again.
	static final int LOST_RETRY_TICKS = 200;

	private static final Map<UUID, Alarm> ALARMS = new HashMap<>();
	private static final Map<UUID, Long> TITLES = new HashMap<>();
	// Each train's throttle at the last check, to tell whether its driver is accelerating.
	private static final Map<String, Integer> THROTTLES = new HashMap<>();
	// Where each parked car was last found on the track.
	private static final Map<String, Placed> PLACED = new HashMap<>();
	private static long tick;

	public record Warning(String label, double seconds) {
		String text() {
			// The look-ahead runs one check past the setting, so cap what it shows.
			double shown = Math.min(seconds, Cache.trainCollisionWarningSeconds);
			return "§c" + label + "§7: §fcollision in §e" + Math.max(1, (int) Math.ceil(shown)) + " s";
		}
	}

	private record Train(ActiveVehicle head, String consist, CollisionForecast.Mover mover) {
	}

	record Placed(TrackedCar car, UUID splineId, double s, long checked) {
	}

	private static final class Alarm {
		final Player player;
		final BossBar bar;
		double seconds;
		long nextSound;
		long lastWarned;

		Alarm(Player player, BossBar bar) {
			this.player = player;
			this.bar = bar;
		}
	}

	private TrainCollisionWarning() {
	}

	public static void tick(Collection<ActiveVehicle> vehicles) {
		tick++;
		if (!Cache.trainCollisionWarning) {
			clear();
			return;
		}
		if (tick % Cache.trainCollisionWarningCheckTicks == 0) {
			show(check(vehicles));
		}
		ring();
	}

	public static void clear() {
		for (Alarm alarm : ALARMS.values()) {
			alarm.bar.removeAll();
		}
		ALARMS.clear();
		TITLES.clear();
		THROTTLES.clear();
		PLACED.clear();
	}

	/** The warning each rider should see now, the soonest when several apply. */
	public static Map<Player, Warning> check(Collection<ActiveVehicle> vehicles) {
		Map<Player, Warning> warnings = new HashMap<>();
		TrackRegistry registry = VehicleFramework.getTrackRegistry();
		if (registry == null) {
			return warnings;
		}
		// Look one check further ahead, so the first warning still comes the full time before.
		double horizon = Cache.trainCollisionWarningSeconds + Cache.trainCollisionWarningCheckTicks / 20.0;
		double margin = Cache.trainCollisionWarningMargin;
		List<TrackedCar> saved = trackedCars();
		Map<String, String> parents = parents(saved, vehicles);
		List<Train> trains = new ArrayList<>();
		Map<String, Integer> throttles = new HashMap<>();
		long now = System.currentTimeMillis();
		for (ActiveVehicle v : vehicles) {
			if (!v.isTrain() || v.isDestroyed() || v.hasParent() || !v.getTrainHandler().isBound()) {
				continue;
			}
			double raw = v.getAccessPanel() == null ? 0 : v.getAccessPanel().getSpeed();
			Throttle throttle = v.getThrottle();
			int limit = throttle == null ? 0 : throttleLimit(v, throttle, now);
			CollisionForecast.Mover motion = motion(raw, throttle, THROTTLES.get(v.getUUID()),
					Cache.trainCollisionWarningCheckTicks, limit);
			if (throttle != null) {
				throttles.put(v.getUUID(), throttle.getCurrent());
			}
			TrainPath path = v.getTrainHandler().collisionPath(motion.distance(horizon) + margin);
			if (path != null) {
				trains.add(new Train(v, root(v.getUUID(), parents), new CollisionForecast.Mover(
						path, motion.speed(), motion.acceleration(), motion.topSpeed())));
			}
		}
		THROTTLES.clear();
		THROTTLES.putAll(throttles);
		List<Train> others = new ArrayList<>(trains);
		others.addAll(parked(registry, saved, vehicles, parents));
		for (Train a : trains) {
			if (a.mover().standing()) {
				continue;
			}
			List<CollisionForecast.Sweep> sweeps = new ArrayList<>();
			for (CollisionForecast.Mover us : a.mover().cases()) {
				sweeps.add(CollisionForecast.sweep(us, horizon, margin));
			}
			CollisionForecast.Contact first = null;
			Train hit = null;
			for (Train b : others) {
				if (b == a || sameConsist(a.consist(), b.consist())) {
					continue;
				}
				for (CollisionForecast.Sweep sweep : sweeps) {
					CollisionForecast.Contact contact = sweep.first(b.mover(),
							first == null ? Double.POSITIVE_INFINITY : first.seconds());
					if (contact != null) {
						first = contact;
						hit = b;
					}
				}
			}
			if (first == null) {
				continue;
			}
			warn(warnings, a.head(), new Warning(label(first.kind()), first.seconds()));
			// A moving train works out its own warning; a standing one cannot.
			if (hit.head() != null && hit.mover().standing()) {
				warn(warnings, hit.head(), new Warning("Train approaching", first.seconds()));
			}
		}
		return warnings;
	}

	/**
	 * How a train is moving, without its path. Speed is in blocks a tick; the throttle
	 * sets speed in proportion. A throttle that rose since the last check is assumed to
	 * keep rising as fast as it can, one point a tick, to {@code limit}: the rise may
	 * have begun just before the check, so the rate seen understates it.
	 */
	static CollisionForecast.Mover motion(double raw, Throttle throttle, Integer previous, int checkTicks,
			int limit) {
		double speed = TrackSplineMotion.stopped(raw) ? 0 : Math.abs(raw) * 20;
		if (throttle == null || previous == null) {
			return new CollisionForecast.Mover(null, speed);
		}
		int now = throttle.getCurrent();
		boolean rising = Math.abs(now) > Math.abs(previous) && (previous == 0 || (now > 0) == (previous > 0));
		if (!rising) {
			return new CollisionForecast.Mover(null, speed);
		}
		double perPoint = Math.abs(raw) / Math.abs(now) * 20;
		double pointsPerSecond = Math.max(THROTTLE_POINTS_PER_TICK, (Math.abs(now) - Math.abs(previous))
				/ (double) checkTicks) * 20;
		return new CollisionForecast.Mover(null, speed, perPoint * pointsPerSecond,
				perPoint * Math.max(Math.abs(now), limit));
	}

	/**
	 * The furthest the throttle can go in its current direction, as the engine allows it:
	 * a damaged engine is held to its health, and a locomotive overdrives past 100 only
	 * when healthy, going forwards, and not cooling down.
	 */
	static int throttleLimit(ActiveVehicle v, Throttle throttle, long now) {
		boolean forward = throttle.getCurrent() >= 0;
		int limit = forward ? throttle.getMax() : -throttle.getMin();
		VehicleComponent engine = v.getComponent(Component.ENGINE);
		if (engine == null || engine.getHealthData() == null) {
			return limit;
		}
		int health = engine.getHealthData().getHealthPercentage();
		if (health < 100 || !forward) {
			return Math.min(limit, health);
		}
		if (!v.isLocomotive()) {
			return limit;
		}
		LocomotiveOverdrive overdrive = v.getTrainHandler().getOverdrive();
		boolean cooling = overdrive != null && overdrive.cooldownSeconds(now) > 0;
		return Math.min(limit, cooling ? 100 : 120);
	}

	static boolean sameConsist(String a, String b) {
		return a != null && a.equalsIgnoreCase(b);
	}

	static String label(CollisionForecast.Kind kind) {
		return switch (kind) {
			case ONCOMING -> "Oncoming train";
			case STOPPED -> "Stopped train ahead";
			case AHEAD -> "Train ahead";
			case BEHIND -> "Train closing from behind";
		};
	}

	private static List<TrackedCar> trackedCars() {
		VehicleRepository repository = VehicleFramework.getVehicleRepository();
		return repository == null ? List.of() : repository.trackedCars();
	}

	/**
	 * Each car's parent, lower case: saved links first, then the live links of loaded
	 * cars, which are newer. A car newly coupled to a loaded train has not been saved
	 * with that parent yet.
	 */
	static Map<String, String> parents(List<TrackedCar> saved, Collection<ActiveVehicle> vehicles) {
		Map<String, String> parents = new HashMap<>();
		for (TrackedCar car : saved) {
			parents.put(car.uuid(), car.parent());
		}
		for (ActiveVehicle v : vehicles) {
			if (v.getUUID() == null || !v.isTrain()) {
				continue;
			}
			String parent = v.hasParent() && v.getParent() != null ? v.getParent().getUUID()
					: v.getTrainHandler().getPendingParent();
			parents.put(lower(v.getUUID()), lower(parent));
		}
		return parents;
	}

	// The consist a car belongs to is named by its furthest parent.
	static String root(String uuid, Map<String, String> parents) {
		String current = lower(uuid);
		for (int i = 0; i < 64 && parents.get(current) != null; i++) {
			current = parents.get(current);
		}
		return current;
	}

	private static String lower(String uuid) {
		return uuid == null || uuid.isBlank() ? null : uuid.toLowerCase(Locale.ROOT);
	}

	/**
	 * Cars not spawned, each standing where it was saved. A car spawns only once a player
	 * is near, and until its parent spawns too it is a head of its own, so each counts as one.
	 */
	private static List<Train> parked(TrackRegistry registry, List<TrackedCar> saved,
			Collection<ActiveVehicle> vehicles, Map<String, String> parents) {
		Set<String> loaded = new HashSet<>();
		for (ActiveVehicle v : vehicles) {
			if (v.getUUID() != null) {
				loaded.add(lower(v.getUUID()));
			}
		}
		PLACED.keySet().retainAll(parents.keySet());
		List<Train> out = new ArrayList<>();
		for (TrackedCar car : saved) {
			if (loaded.contains(car.uuid())) {
				continue;
			}
			Placed placed = place(registry, car);
			TrackSpline spline = placed.splineId() == null ? null : registry.get(placed.splineId()).orElse(null);
			if (spline == null) {
				continue;
			}
			TrainPath path = TrainPath.standing(spline, placed.s(), Cache.trainCollisionWarningParkedHalfLength);
			out.add(new Train(null, root(car.uuid(), parents), new CollisionForecast.Mover(path, 0)));
		}
		return out;
	}

	/**
	 * Where a parked car is on the track now. Digging can re-id its spline or shift arc
	 * lengths after it was saved, so its saved position is only trusted while the track
	 * there still runs under where the car stood; otherwise the nearest track is found.
	 */
	static Placed place(TrackRegistry registry, TrackedCar car) {
		Placed cached = PLACED.get(car.uuid());
		if (cached != null && cached.car().equals(car)) {
			if (cached.splineId() == null ? tick - cached.checked() < LOST_RETRY_TICKS
					: under(registry, cached.splineId(), cached.s(), car)) {
				return cached;
			}
		}
		UUID saved = uuid(car.splineId());
		Placed placed;
		if (saved != null && under(registry, saved, car.s(), car)) {
			placed = new Placed(car, saved, car.s(), tick);
		} else {
			placed = nearest(registry, car);
		}
		PLACED.put(car.uuid(), placed);
		return placed;
	}

	private static Placed nearest(TrackRegistry registry, TrackedCar car) {
		double y = car.y() - Cache.trackVehicleYOffset;
		TrackSpline best = null;
		double bestS = 0;
		double bestD = Double.POSITIVE_INFINITY;
		for (TrackSpline spline : registry.inWorld(car.world())) {
			double s = spline.nearestS(car.x(), y, car.z());
			TrackPose at = spline.sampleAt(s);
			double d = Math.hypot(at.x - car.x(), at.z - car.z());
			if (d <= ON_TRACK && Math.abs(at.y - y) <= ON_TRACK && d < bestD) {
				best = spline;
				bestS = s;
				bestD = d;
			}
		}
		return new Placed(car, best == null ? null : best.getId(), bestS, tick);
	}

	private static boolean under(TrackRegistry registry, UUID splineId, double s, TrackedCar car) {
		TrackSpline spline = registry.get(splineId).orElse(null);
		if (spline == null) {
			return false;
		}
		TrackPose at = spline.sampleAt(s);
		return Math.hypot(at.x - car.x(), at.z - car.z()) <= ON_TRACK
				&& Math.abs(at.y - (car.y() - Cache.trackVehicleYOffset)) <= ON_TRACK;
	}

	private static UUID uuid(String id) {
		try {
			return UUID.fromString(id);
		} catch (IllegalArgumentException malformed) {
			return null;
		}
	}

	private static void warn(Map<Player, Warning> warnings, ActiveVehicle head, Warning warning) {
		ActiveVehicle car = head;
		for (int i = 0; i < 64 && car != null; i++) {
			if (car.getSeatHandler() != null) {
				for (Entity rider : car.getSeatHandler().getPassengers()) {
					if (rider instanceof Player player) {
						warnings.merge(player, warning, (old, next) -> next.seconds() < old.seconds() ? next : old);
					}
				}
			}
			car = car.isTrain() && car.getTrainHandler().hasChild() ? car.getTrainHandler().getChild() : null;
		}
	}

	static void show(Map<Player, Warning> warnings) {
		Map<UUID, Player> current = new HashMap<>();
		for (Player player : warnings.keySet()) {
			current.put(player.getUniqueId(), player);
		}
		Iterator<Map.Entry<UUID, Alarm>> it = ALARMS.entrySet().iterator();
		while (it.hasNext()) {
			Alarm alarm = it.next().getValue();
			Player now = current.get(alarm.player.getUniqueId());
			boolean replaced = now != null && now != alarm.player;
			if (replaced || (now == null && tick - alarm.lastWarned >= CLEAR_TICKS)) {
				alarm.bar.removeAll();
				it.remove();
			}
		}
		for (Map.Entry<Player, Warning> entry : warnings.entrySet()) {
			Player player = entry.getKey();
			Warning warning = entry.getValue();
			Alarm alarm = ALARMS.get(player.getUniqueId());
			if (alarm == null) {
				BossBar bar = Bukkit.createBossBar(warning.text(), BarColor.RED, BarStyle.SEGMENTED_10);
				bar.addPlayer(player);
				alarm = new Alarm(player, bar);
				alarm.nextSound = tick;
				ALARMS.put(player.getUniqueId(), alarm);
				Long titled = TITLES.get(player.getUniqueId());
				if (titled == null || tick - titled >= TITLE_TICKS) {
					player.sendTitle("§c§lCollision warning", warning.text(), 0, 50, 10);
					TITLES.put(player.getUniqueId(), tick);
				}
			}
			alarm.seconds = warning.seconds();
			alarm.lastWarned = tick;
			alarm.bar.setTitle(warning.text());
			alarm.bar.setProgress(Math.max(0, Math.min(1, warning.seconds() / Cache.trainCollisionWarningSeconds)));
		}
	}

	private static void ring() {
		for (Alarm alarm : ALARMS.values()) {
			// A lapsed warning stays on screen briefly but stops ringing.
			if (tick < alarm.nextSound || tick - alarm.lastWarned >= Cache.trainCollisionWarningCheckTicks) {
				continue;
			}
			alarm.player.playSound(alarm.player.getLocation(), Cache.trainCollisionWarningSound,
					Cache.trainCollisionWarningSoundVolume, Cache.trainCollisionWarningSoundPitch);
			int interval = Cache.trainCollisionWarningSoundTicks;
			alarm.nextSound = tick + (alarm.seconds <= 10 ? Math.max(1, interval / 2) : interval);
		}
	}
}
