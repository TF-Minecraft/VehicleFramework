package net.tfminecraft.vehicleframework.tracks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import net.tfminecraft.vehicleframework.VehicleFramework;
import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.data.HealthData;
import net.tfminecraft.vehicleframework.database.TrackedCar;
import net.tfminecraft.vehicleframework.database.VehicleRepository;
import net.tfminecraft.vehicleframework.enums.Component;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.component.VehicleComponent;
import net.tfminecraft.vehicleframework.vehicles.component.propulsion.Throttle;
import net.tfminecraft.vehicleframework.vehicles.handlers.SeatHandler;
import net.tfminecraft.vehicleframework.vehicles.handlers.TrainHandler;
import net.tfminecraft.vehicleframework.vehicles.handlers.train.LocomotiveOverdrive;
import net.tfminecraft.vehicleframework.vehicles.util.AccessPanel;

class TrainCollisionWarningTest {
	@TempDir Path directory;
	private MockedStatic<Bukkit> bukkit;
	private final List<BossBar> bars = new ArrayList<>();
	private final Map<String, Object> saved = new HashMap<>();
	private TrackRegistry registry;
	private UUID line;
	private final List<TrackedCar> parked = new ArrayList<>();

	@BeforeEach
	void setUp() throws Exception {
		bukkit = mockStatic(Bukkit.class);
		bukkit.when(() -> Bukkit.createBossBar(anyString(), any(BarColor.class), any(BarStyle.class)))
				.thenAnswer(call -> {
					BossBar bar = mock(BossBar.class);
					bars.add(bar);
					return bar;
				});
		line = UUID.randomUUID();
		List<double[]> points = new ArrayList<>();
		for (int z = 0; z <= 300; z += 10) {
			points.add(new double[]{0, 64, z});
		}
		new TrackStore(directory.toFile()).save(TrackSpline.fromPoints(line, "world", false, points));
		registry = new TrackRegistry(directory.toFile());
		registry.loadFromDisk();
		VehicleRepository repository = mock(VehicleRepository.class);
		when(repository.trackedCars()).thenReturn(parked);
		swap("trackRegistry", registry);
		swap("vehicleRepository", repository);
		Cache.trainCollisionWarning = true;
		Cache.trainCollisionWarningSeconds = 30;
		Cache.trainCollisionWarningCheckTicks = 1;
		Cache.trainCollisionWarningMargin = 2;
		Cache.trainCollisionWarningParkedHalfLength = 5;
		Cache.trainCollisionWarningSoundTicks = 40;
		Cache.trackVehicleYOffset = 0.5;
		TrainCollisionWarning.clear();
	}

	@AfterEach
	void tearDown() throws Exception {
		TrainCollisionWarning.clear();
		for (Map.Entry<String, Object> entry : saved.entrySet()) {
			field(entry.getKey()).set(null, entry.getValue());
		}
		bukkit.close();
	}

	@Test
	void warnsTheRunnerAndTheStandingTrainItIsHeadingFor() {
		Player driver = player();
		Player guard = player();
		Player waiting = player();
		ActiveVehicle runner = train("A", 0.5, path(0, 300, 10), seats(driver, mock(Entity.class)));
		ActiveVehicle van = car(seats(guard));
		when(runner.getTrainHandler().hasChild()).thenReturn(true);
		when(runner.getTrainHandler().getChild()).thenReturn(van);
		ActiveVehicle standing = train("B", 0, path(100, 10, 10), seats(waiting));
		// Part of the runner's own train, unloaded in a chunk ahead of it.
		parked.add(parkedCar("tail", 50, "a"));
		// Loaded, so its live position counts instead.
		parked.add(parkedCar("b", 20, null));
		// Saved on no track at all, and far from the line.
		parked.add(new TrackedCar("lost", "not-a-uuid", 20, null, "world", 500, 64.5, 20));
		parked.add(new TrackedCar("gone", UUID.randomUUID().toString(), 20, null, "world", 500, 64.5, 20));
		parked.add(parkedCar("far", 250, "farhead"));
		parked.add(parkedCar("farhead", 260, null));

		Map<Player, TrainCollisionWarning.Warning> warnings = TrainCollisionWarning.check(
				List.of(runner, standing, mock(ActiveVehicle.class), wrecked(), coupled(), derailed(), pathless()));

		assertEquals(3, warnings.size());
		assertEquals("Stopped train ahead", warnings.get(driver).label());
		assertEquals(8.8, warnings.get(driver).seconds(), 1e-9);
		assertEquals(warnings.get(driver), warnings.get(guard));
		assertEquals("Train approaching", warnings.get(waiting).label());
		// A second check reuses where the parked cars were found.
		assertEquals(8.8, TrainCollisionWarning.check(List.of(runner, standing)).get(driver).seconds(), 1e-9);
	}

	@Test
	void parkedCarsAreFoundAgainAfterTheirTrackIsEdited() throws Exception {
		Player driver = player();
		ActiveVehicle runner = train("A", 0.5, path(0, 300, 10), seats(driver));
		// Saved on a spline since re-id'd by a dig, standing at z 105.
		parked.add(new TrackedCar("moved", UUID.randomUUID().toString(), 999, null, "world", 0, 64.5, 105));
		assertEquals(8.8, TrainCollisionWarning.check(List.of(runner)).get(driver).seconds(), 1e-9);
		parked.clear();
		// On the same spline, but its arc length shifted when the line was trimmed.
		parked.add(new TrackedCar("shifted", line.toString(), 40, null, "world", 0, 64.5, 105));
		assertEquals(8.8, TrainCollisionWarning.check(List.of(runner)).get(driver).seconds(), 1e-9);
		assertEquals(8.8, TrainCollisionWarning.check(List.of(runner)).get(driver).seconds(), 1e-9);
		swap("vehicleRepository", null);
		assertTrue(TrainCollisionWarning.check(List.of(runner)).isEmpty());
		swap("trackRegistry", null);
		assertTrue(TrainCollisionWarning.check(List.of(runner)).isEmpty());
	}

	@Test
	void lostCarsAreLookedForAgainOnlyAfterAWhile() {
		TrackedCar lost = new TrackedCar("lost", line.toString(), 50, null, "world", 500, 64.5, 50);
		TrainCollisionWarning.Placed first = TrainCollisionWarning.place(registry, lost);
		assertNull(first.splineId());
		assertTrue(first == TrainCollisionWarning.place(registry, lost));
		for (int i = 0; i < TrainCollisionWarning.LOST_RETRY_TICKS; i++) {
			TrainCollisionWarning.tick(List.of());
		}
		assertFalse(first == TrainCollisionWarning.place(registry, lost));
		// A car that moved since it was cached is placed again.
		assertEquals(line, TrainCollisionWarning.place(registry, parkedCar("lost", 50, null)).splineId());
		// Track at another height does not count.
		TrackedCar above = new TrackedCar("high", line.toString(), 50, null, "world", 0, 80, 50);
		assertNull(TrainCollisionWarning.place(registry, above).splineId());
	}

	@Test
	void onlyTheLocomotiveExplodesWhenPushingCars() {
		Player driver = player();
		// Reversing: the locomotive is the last 10 of 30 blocks, pushing two cars.
		ActiveVehicle pusher = train("A", 0.5,
				new TrainPath(List.of(new TrainRoute.Piece(line, 0, 1, 300, 0, false, 300)), 30, 0, 10), seats(driver));
		// A standing train from 100 to 140 with its locomotive at the far end: the pushed
		// cars pass through its cars, but not through the locomotive.
		ActiveVehicle standing = train("B", 0,
				new TrainPath(List.of(new TrainRoute.Piece(line, 100, 1, 40, 0, false, 300)), 40, 30, 40), seats());
		// The pusher's locomotive, 10 blocks in, plus the margin reaches 100 after 88 blocks.
		assertEquals(8.8, TrainCollisionWarning.check(List.of(pusher, standing)).get(driver).seconds(), 1e-9);
		// An unspawned car may spawn before its locomotive and then explodes like one, so the
		// pushed cars count: their front plus the margin reaches 100 after 68 blocks.
		parked.add(parkedCar("wagon", 105, "elsewhere"));
		assertEquals(6.8, TrainCollisionWarning.check(List.of(pusher)).get(driver).seconds(), 1e-9);
	}

	@Test
	void bothTrainsAreWarnedWhenOneCatchesTheOther() {
		Player fast = player();
		Player slow = player();
		ActiveVehicle chaser = train("A", 0.5, path(0, 300, 10), seats(fast));
		ActiveVehicle ahead = train("B", 0.25, path(50, 250, 10), seats(slow));
		Map<Player, TrainCollisionWarning.Warning> warnings = TrainCollisionWarning.check(List.of(chaser, ahead));
		assertEquals("Train ahead", warnings.get(fast).label());
		assertEquals(7.6, warnings.get(fast).seconds(), 1e-9);
		assertEquals("Train closing from behind", warnings.get(slow).label());
		// Without the margin ahead of it, the slower train meets the faster one at 8.0 s.
		assertEquals(8.0, warnings.get(slow).seconds(), 1e-9);
	}

	@Test
	void newlyCoupledCarsAreStillPartOfTheTrain() {
		Player driver = player();
		ActiveVehicle loco = train("A", 0.5, path(0, 300, 10), seats(driver));
		// B was coupled to A since it was last saved; C is B's saved child, unloaded ahead.
		ActiveVehicle coupled = car(seats());
		when(coupled.getUUID()).thenReturn("B");
		when(coupled.hasParent()).thenReturn(true);
		when(coupled.getParent()).thenReturn(loco);
		parked.add(parkedCar("b", 40, null));
		parked.add(parkedCar("c", 60, "b"));
		// A car waiting for its parent to load still belongs to it.
		ActiveVehicle waiting = car(seats());
		when(waiting.getUUID()).thenReturn("D");
		when(waiting.getTrainHandler().getPendingParent()).thenReturn("A");
		parked.add(parkedCar("e", 80, "d"));
		assertTrue(TrainCollisionWarning.check(List.of(loco, coupled, waiting)).isEmpty());
	}

	@Test
	void openingTheThrottleWarnsAsIfTheTrainKeepsAccelerating() {
		Player driver = player();
		ActiveVehicle runner = train("A", 0.36, path(0, 300, 10), seats(driver));
		Throttle throttle = new Throttle("Throttle", 120, -100, null);
		when(runner.getThrottle()).thenReturn(throttle);
		ActiveVehicle standing = train("B", 0, path(250, 10, 10), seats());
		Cache.trainCollisionWarningCheckTicks = 10;
		List<ActiveVehicle> vehicles = List.of(runner, standing);

		// Half throttle, held: 7.2 blocks a second takes about 33 seconds to reach the train.
		throttle.setThrottle(50);
		assertTrue(TrainCollisionWarning.check(vehicles).isEmpty());
		assertTrue(TrainCollisionWarning.check(vehicles).isEmpty());
		// Opening it 10 points over the 10-tick check means 20 points a second, up to 120.
		throttle.setThrottle(60);
		when(runner.getAccessPanel().getSpeed()).thenReturn(0.432);
		TrainCollisionWarning.Warning rising = TrainCollisionWarning.check(vehicles).get(driver);
		assertTrue(rising.seconds() < 25, "seconds " + rising.seconds());
		// Held there, the warning goes back to the steady speed.
		TrainCollisionWarning.Warning held = TrainCollisionWarning.check(vehicles).get(driver);
		assertTrue(held == null || held.seconds() > rising.seconds());
	}

	@Test
	void motionTreatsOnlyARisingThrottleAsAcceleration() {
		Throttle throttle = new Throttle("Throttle", 120, -100, null);
		throttle.setThrottle(50);
		assertSteady(TrainCollisionWarning.motion(0.36, null, 40, 10, 120));
		assertSteady(TrainCollisionWarning.motion(0.36, throttle, null, 10, 120));
		assertSteady(TrainCollisionWarning.motion(0.36, throttle, 50, 10, 120));
		assertSteady(TrainCollisionWarning.motion(0.36, throttle, -40, 10, 120));
		CollisionForecast.Mover forward = TrainCollisionWarning.motion(0.36, throttle, 0, 10, 120);
		assertEquals(7.2, forward.speed(), 1e-9);
		// 0.144 blocks a second per point, rising 100 points a second, up to 120 points.
		assertEquals(14.4, forward.acceleration(), 1e-9);
		assertEquals(17.28, forward.topSpeed(), 1e-9);
		assertEquals(10.8, TrainCollisionWarning.motion(0.36, throttle, 0, 10, 75).topSpeed(), 1e-9);
		// A rise seen only at the end of a check is taken at the full rate, a point a tick.
		assertEquals(2.88, TrainCollisionWarning.motion(0.36, throttle, 49, 10, 120).acceleration(), 1e-9);
		throttle.setThrottle(-30);
		assertEquals(14.4, TrainCollisionWarning.motion(-0.216, throttle, -20, 10, 100).topSpeed(), 1e-9);
		assertEquals(0, TrainCollisionWarning.motion(0, throttle, -20, 10, 100).speed());
	}

	@Test
	void throttleLimitFollowsEngineHealthAndOverdrive() {
		Throttle throttle = new Throttle("Throttle", 120, -100, null);
		throttle.setThrottle(50);
		ActiveVehicle loco = mock(ActiveVehicle.class);
		assertEquals(120, TrainCollisionWarning.throttleLimit(loco, throttle, 0));
		VehicleComponent engine = mock(VehicleComponent.class);
		when(loco.getComponent(Component.ENGINE)).thenReturn(engine);
		assertEquals(120, TrainCollisionWarning.throttleLimit(loco, throttle, 0));
		HealthData health = mock(HealthData.class);
		when(engine.getHealthData()).thenReturn(health);
		when(health.getHealthPercentage()).thenReturn(60);
		assertEquals(60, TrainCollisionWarning.throttleLimit(loco, throttle, 0));
		when(health.getHealthPercentage()).thenReturn(100);
		assertEquals(120, TrainCollisionWarning.throttleLimit(loco, throttle, 0));
		when(loco.isLocomotive()).thenReturn(true);
		TrainHandler handler = mock(TrainHandler.class);
		when(loco.getTrainHandler()).thenReturn(handler);
		assertEquals(120, TrainCollisionWarning.throttleLimit(loco, throttle, 0));
		LocomotiveOverdrive overdrive = new LocomotiveOverdrive();
		when(handler.getOverdrive()).thenReturn(overdrive);
		assertEquals(120, TrainCollisionWarning.throttleLimit(loco, throttle, 0));
		// Dropping out of overdrive starts its cooldown.
		overdrive.update(120, 1_000);
		overdrive.update(100, 2_000);
		assertEquals(100, TrainCollisionWarning.throttleLimit(loco, throttle, 3_000));
		throttle.setThrottle(-20);
		assertEquals(100, TrainCollisionWarning.throttleLimit(loco, throttle, 3_000));
	}

	@Test
	void flashesOnceThenCountsDownAndClearsWithoutFlicker() {
		Player driver = player();
		ActiveVehicle runner = train("A", 0.5, path(0, 300, 10), seats(driver));
		ActiveVehicle standing = train("B", 0, path(100, 10, 10), seats());
		List<ActiveVehicle> vehicles = List.of(runner, standing);
		TrainCollisionWarning.tick(vehicles);
		assertEquals(1, bars.size());
		BossBar bar = bars.get(0);
		verify(bar).addPlayer(driver);
		verify(driver).sendTitle(eq("§c§lCollision warning"), eq("§cStopped train ahead§7: §fcollision in §e9 s"),
				eq(0), eq(50), eq(10));
		verify(bar).setProgress(8.8 / 30);

		// The danger passes: the bar stays briefly, silent, in case it comes straight back.
		when(runner.getAccessPanel().getSpeed()).thenReturn(0.0);
		for (int i = 0; i < TrainCollisionWarning.CLEAR_TICKS - 1; i++) {
			TrainCollisionWarning.tick(vehicles);
		}
		verify(bar, never()).removeAll();
		verify(driver, times(1)).playSound(any(Location.class), anyString(), anyFloat(), anyFloat());
		TrainCollisionWarning.tick(vehicles);
		verify(bar).removeAll();

		// Back within the title cooldown: a new bar, but no second title.
		when(runner.getAccessPanel().getSpeed()).thenReturn(0.5);
		TrainCollisionWarning.tick(vehicles);
		assertEquals(2, bars.size());
		verify(driver, times(1)).sendTitle(anyString(), anyString(), anyInt(), anyInt(), anyInt());

		// Rejoining gives a new player object for the same account.
		UUID account = driver.getUniqueId();
		Player rejoined = player();
		when(rejoined.getUniqueId()).thenReturn(account);
		TrainCollisionWarning.show(Map.of(rejoined, new TrainCollisionWarning.Warning("Train ahead", 45)));
		verify(bars.get(1)).removeAll();
		verify(bars.get(2)).setTitle("§cTrain ahead§7: §fcollision in §e30 s");
		verify(bars.get(2)).setProgress(1.0);
	}

	@Test
	void alarmRingsFasterWhenCloseAndStopsWhenDisabled() {
		Player driver = player();
		ActiveVehicle runner = train("A", 0.5, path(0, 300, 10), seats(driver));
		ActiveVehicle standing = train("B", 0, path(100, 10, 10), seats());
		List<ActiveVehicle> vehicles = List.of(runner, standing);
		Cache.trainCollisionWarningSoundTicks = 2;
		for (int i = 0; i < 4; i++) {
			TrainCollisionWarning.tick(vehicles);
		}
		// Under 10 seconds away, the alarm sounds every tick instead of every other.
		verify(driver, times(4)).playSound(any(Location.class), eq(Cache.trainCollisionWarningSound),
				anyFloat(), anyFloat());

		Cache.trainCollisionWarning = false;
		TrainCollisionWarning.tick(vehicles);
		verify(bars.get(0)).removeAll();
	}

	@Test
	void slowAlarmWhileTheDangerIsStillFarOff() {
		Player driver = player();
		ActiveVehicle runner = train("A", 0.1, path(0, 300, 10), seats(driver));
		ActiveVehicle standing = train("B", 0, path(40, 10, 10), seats());
		Cache.trainCollisionWarningSoundTicks = 3;
		for (int i = 0; i < 4; i++) {
			TrainCollisionWarning.tick(List.of(runner, standing));
		}
		verify(driver, times(2)).playSound(any(Location.class), anyString(), anyFloat(), anyFloat());
	}

	@Test
	void labelsRootsAndConsists() {
		assertEquals("Oncoming train", TrainCollisionWarning.label(CollisionForecast.Kind.ONCOMING));
		assertEquals("Stopped train ahead", TrainCollisionWarning.label(CollisionForecast.Kind.STOPPED));
		assertEquals("Train ahead", TrainCollisionWarning.label(CollisionForecast.Kind.AHEAD));
		assertEquals("Train closing from behind", TrainCollisionWarning.label(CollisionForecast.Kind.BEHIND));
		Map<String, String> parents = new HashMap<>();
		parents.put("c", "b");
		parents.put("b", "a");
		parents.put("a", null);
		parents.put("x", "y");
		parents.put("y", "x");
		assertEquals("a", TrainCollisionWarning.root("C", parents));
		assertEquals("loco", TrainCollisionWarning.root("loco", parents));
		assertTrue(List.of("x", "y").contains(TrainCollisionWarning.root("x", parents)));
		assertNull(TrainCollisionWarning.root(" ", parents));
		assertTrue(TrainCollisionWarning.sameConsist("A", "a"));
		assertFalse(TrainCollisionWarning.sameConsist(null, "a"));
		ActiveVehicle nameless = car(seats());
		ActiveVehicle cart = mock(ActiveVehicle.class);
		when(cart.getUUID()).thenReturn("cart");
		assertTrue(TrainCollisionWarning.parents(List.of(), List.of(nameless, cart)).isEmpty());
	}

	private static void assertSteady(CollisionForecast.Mover motion) {
		assertEquals(7.2, motion.speed(), 1e-9);
		assertEquals(0, motion.acceleration());
		assertEquals(7.2, motion.topSpeed(), 1e-9);
	}

	private TrackedCar parkedCar(String uuid, double s, String parent) {
		return new TrackedCar(uuid, line.toString(), s, parent, "world", 0, 64.5, s);
	}

	private ActiveVehicle wrecked() {
		ActiveVehicle v = train("W", 0.5, path(0, 10, 5), seats());
		when(v.isDestroyed()).thenReturn(true);
		return v;
	}

	private ActiveVehicle coupled() {
		ActiveVehicle v = train("C", 0.5, path(0, 10, 5), seats());
		when(v.hasParent()).thenReturn(true);
		return v;
	}

	private ActiveVehicle derailed() {
		ActiveVehicle v = train("D", 0.5, path(0, 10, 5), seats());
		when(v.getTrainHandler().isBound()).thenReturn(false);
		return v;
	}

	private ActiveVehicle pathless() {
		ActiveVehicle v = train("P", 0.5, null, seats());
		when(v.getAccessPanel()).thenReturn(null);
		return v;
	}

	private ActiveVehicle train(String uuid, double speed, TrainPath path, SeatHandler seats) {
		ActiveVehicle vehicle = car(seats);
		when(vehicle.getUUID()).thenReturn(uuid);
		AccessPanel panel = spy(new AccessPanel());
		panel.setSpeed(speed);
		when(vehicle.getAccessPanel()).thenReturn(panel);
		TrainHandler handler = vehicle.getTrainHandler();
		when(handler.isBound()).thenReturn(true);
		when(handler.collisionPath(anyDouble())).thenReturn(path);
		return vehicle;
	}

	private static ActiveVehicle car(SeatHandler seats) {
		ActiveVehicle vehicle = mock(ActiveVehicle.class);
		when(vehicle.isTrain()).thenReturn(true);
		when(vehicle.getSeatHandler()).thenReturn(seats);
		TrainHandler handler = mock(TrainHandler.class);
		when(vehicle.getTrainHandler()).thenReturn(handler);
		return vehicle;
	}

	private static SeatHandler seats(Entity... riders) {
		SeatHandler seats = mock(SeatHandler.class);
		when(seats.getPassengers()).thenReturn(new ArrayList<>(List.of(riders)));
		return seats;
	}

	private TrainPath path(double s, double length, double train) {
		return new TrainPath(List.of(new TrainRoute.Piece(line, s, 1, length, 0, false, 300)), train);
	}

	private static Player player() {
		Player player = mock(Player.class);
		when(player.getUniqueId()).thenReturn(UUID.randomUUID());
		when(player.getLocation()).thenReturn(new Location(null, 0, 64, 0));
		return player;
	}

	private void swap(String name, Object value) throws Exception {
		Field field = field(name);
		saved.putIfAbsent(name, field.get(null));
		field.set(null, value);
	}

	private static Field field(String name) throws Exception {
		Field field = VehicleFramework.class.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}
}
