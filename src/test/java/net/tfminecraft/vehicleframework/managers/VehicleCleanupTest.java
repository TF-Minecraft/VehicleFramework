package net.tfminecraft.vehicleframework.managers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.data.OwnerData;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;

class VehicleCleanupTest {
	private static final long MIN = 60_000L;
	private Set<String> worlds, keep;
	private long idle;
	private final World tutorial = mock(World.class), main = mock(World.class);
	private final VehicleCleanup cleanup = new VehicleCleanup();

	@BeforeEach
	void configure() {
		worlds = Cache.cleanupWorlds; keep = Cache.cleanupKeepOwners; idle = Cache.cleanupIdleMillis;
		when(tutorial.getName()).thenReturn("TFMC_S2");
		when(main.getName()).thenReturn("TFMC_Map");
		Cache.cleanupWorlds = new HashSet<>(Set.of("tfmc_s2"));
		Cache.cleanupKeepOwners = new HashSet<>(Set.of("char_demo"));
		Cache.cleanupIdleMillis = 30 * MIN;
	}

	@AfterEach
	void restore() {
		Cache.cleanupWorlds = worlds; Cache.cleanupKeepOwners = keep; Cache.cleanupIdleMillis = idle;
	}

	private ActiveVehicle vehicle(String id, World world, String owner) {
		ActiveVehicle v = mock(ActiveVehicle.class, RETURNS_DEEP_STUBS);
		when(v.getUUID()).thenReturn(id);
		when(v.getEntity().getWorld()).thenReturn(world);
		OwnerData data = new OwnerData();
		data.setOwner(owner);
		when(v.getOwnerData()).thenReturn(data);
		when(v.getSeatHandler().getPassengers()).thenReturn(new ArrayList<>());
		when(v.getEntity().getLocation()).thenReturn(new Location(world, 0, 64, 0));
		return v;
	}

	@Test
	void idleVehiclesOnCleanupWorldsAreDueAfterTheIdleTime() {
		ActiveVehicle built = vehicle("built", tutorial, "player_Saint");
		ActiveVehicle unowned = vehicle("unowned", tutorial, "none");
		ActiveVehicle demo = vehicle("demo", tutorial, "char_demo");
		ActiveVehicle elsewhere = vehicle("elsewhere", main, "player_Saint");
		List<ActiveVehicle> all = List.of(built, unowned, demo, elsewhere, built); // built twice: two entities

		// First seen: the idle clock starts, nothing is due yet.
		assertTrue(cleanup.due(all, 0).isEmpty());
		assertTrue(cleanup.due(all, 29 * MIN).isEmpty());
		// Kept owners and other worlds never come due; a vehicle registered twice is listed once.
		assertEquals(List.of(built, unowned), cleanup.due(all, 30 * MIN));
	}

	@Test
	void aPassengerRestartsTheIdleClock() {
		ActiveVehicle car = vehicle("car", tutorial, "player_Saint");
		List<ActiveVehicle> all = List.of(car);
		cleanup.due(all, 0);
		when(car.getSeatHandler().getPassengers()).thenReturn(new ArrayList<>(List.<Entity>of(mock(Player.class))));
		assertTrue(cleanup.due(all, 25 * MIN).isEmpty()); // occupied: clock now at 25
		when(car.getSeatHandler().getPassengers()).thenReturn(new ArrayList<>());
		assertTrue(cleanup.due(all, 50 * MIN).isEmpty());
		assertEquals(List.of(car), cleanup.due(all, 55 * MIN));
	}

	@Test
	void aMovingVehicleWithoutPassengersIsNotIdle() {
		// a train car behind an occupied locomotive, or a horse-drawn cart: no seat taken, but moving
		ActiveVehicle car = vehicle("car", tutorial, "player_Saint");
		List<ActiveVehicle> all = List.of(car);
		cleanup.due(all, 0);
		when(car.getEntity().getLocation()).thenReturn(new Location(tutorial, 40, 64, 0));
		assertTrue(cleanup.due(all, 20 * MIN).isEmpty()); // moved: clock now at 20
		when(car.getEntity().getLocation()).thenReturn(new Location(tutorial, 40.5, 64, 0.4));
		assertTrue(cleanup.due(all, 45 * MIN).isEmpty()); // drifted under a block: not use
		assertEquals(List.of(car), cleanup.due(all, 50 * MIN));
		when(car.getEntity().getLocation()).thenReturn(null);
		assertEquals(List.of(car), cleanup.due(all, 51 * MIN)); // no location: judged by passengers only
	}

	@Test
	void aReloadedVehicleStartsAFreshClockAndGettingInOrOutCountsAsUse() {
		ActiveVehicle car = vehicle("car", tutorial, "player_Saint");
		cleanup.due(List.of(car), 0);
		// unloaded and loaded again between two checks: a new instance with the same id
		ActiveVehicle reloaded = vehicle("car", tutorial, "player_Saint");
		assertTrue(cleanup.due(List.of(reloaded), 29 * MIN).isEmpty());
		assertTrue(cleanup.due(List.of(reloaded), 58 * MIN).isEmpty());
		cleanup.markUsed(car, 58 * MIN); // the old instance: ignored
		// someone got in and out between checks, at minute 58
		cleanup.markUsed(reloaded, 58 * MIN);
		assertTrue(cleanup.due(List.of(reloaded), 60 * MIN).isEmpty());
		assertEquals(List.of(reloaded), cleanup.due(List.of(reloaded), 88 * MIN));
		cleanup.markUsed(null, 0); // a player who was in no vehicle
		cleanup.markUsed(vehicle(null, tutorial, "x"), 0);
		cleanup.markUsed(vehicle("never-seen", tutorial, "x"), 0);
	}

	@Test
	void unloadedVehiclesAreForgottenAndNoWorldsMeansNoCleanup() {
		ActiveVehicle car = vehicle("car", tutorial, "player_Saint");
		cleanup.due(List.of(car), 0);
		cleanup.due(List.of(), 10 * MIN); // unloaded: forgotten
		assertTrue(cleanup.due(List.of(car), 40 * MIN).isEmpty()); // seen again: a fresh clock
		assertEquals(List.of(car), cleanup.due(List.of(car), 70 * MIN));

		Cache.cleanupWorlds = new HashSet<>();
		assertTrue(cleanup.due(List.of(car), 200 * MIN).isEmpty());
		Cache.cleanupWorlds = new HashSet<>(Set.of("tfmc_s2"));
		assertTrue(cleanup.due(List.of(car), 201 * MIN).isEmpty()); // the clock was cleared too
	}

	@Test
	void vehiclesWithoutAnIdWorldOrOwnerAreHandledSafely() {
		ActiveVehicle noId = vehicle(null, tutorial, "player_A");
		ActiveVehicle destroyed = vehicle("destroyed", tutorial, "player_A");
		when(destroyed.isDestroyed()).thenReturn(true);
		ActiveVehicle noEntity = vehicle("noEntity", tutorial, "player_A");
		when(noEntity.getEntity()).thenReturn(null);
		ActiveVehicle noWorld = vehicle("noWorld", null, "player_A");
		ActiveVehicle noOwnerData = vehicle("noOwnerData", tutorial, "x");
		when(noOwnerData.getOwnerData()).thenReturn(null);
		ActiveVehicle nullOwner = vehicle("nullOwner", tutorial, null);
		List<ActiveVehicle> all = List.of(noId, destroyed, noEntity, noWorld, noOwnerData, nullOwner);
		cleanup.due(all, 0);
		assertEquals(List.of(noOwnerData, nullOwner), cleanup.due(all, 30 * MIN));
	}
}
