package net.tfminecraft.vehicleframework.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.tfminecraft.vehicleframework.enums.SeatType;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.handlers.SeatHandler;
import net.tfminecraft.vehicleframework.vehicles.handlers.TrainHandler;
import net.tfminecraft.vehicleframework.vehicles.seat.Seat;

/** Vehicles log every tick. With the log off nothing is looked at; with it on, the lines are as before. */
class PersistenceLogQuietTest {
	@TempDir Path folder;

	@AfterEach
	void off() {
		PersistenceLog.configure(false, folder.toFile());
	}

	@Test
	void anOffLogDoesNotLookAtTheVehicleOrLoadChunks() {
		PersistenceLog.configure(false, folder.toFile());
		ActiveVehicle vehicle = mock(ActiveVehicle.class);
		Location here = mock(Location.class);
		Location there = mock(Location.class);
		Entity rider = mock(Entity.class);
		Player player = mock(Player.class);

		PersistenceLog.placeCars(vehicle);
		PersistenceLog.applyPose(vehicle, "spline", here, there);
		PersistenceLog.particleOffset(vehicle, here, there);
		PersistenceLog.spawnLoad("cart", here);
		PersistenceLog.spawnQueued("cart", here, "world 1,2");
		PersistenceLog.spawned(vehicle, here);
		PersistenceLog.saveVehicle(vehicle, here);
		PersistenceLog.saveSpawn("cart", here);
		PersistenceLog.loadJson("cart", "uuid", "tug", ConsistData.unbound());
		PersistenceLog.tryLink("before", vehicle);
		PersistenceLog.mount(player, vehicle, new Seat(SeatType.CAPTAIN, "driver"), true, true);
		PersistenceLog.remount(rider, vehicle, "driver");
		PersistenceLog.dismount(rider, vehicle, true);

		verifyNoInteractions(vehicle, here, there, rider, player);
		PersistenceLog.unload(vehicle, "unload");
		// Only the throttle bookkeeping is cleared; the vehicle is not described.
		verify(vehicle, atLeastOnce()).getUUID();
		verifyNoMoreInteractions(vehicle);
		assertFalse(Files.exists(folder.resolve("logs/persistence.log")));
	}

	@Test
	void anOffLogStillCountsSpawnCycles() {
		PersistenceLog.configure(false, folder.toFile());
		int before = PersistenceLog.spawnCycle();
		PersistenceLog.spawnManagerStart(4);
		assertEquals(before + 1, PersistenceLog.spawnCycle());
	}

	@Test
	void anOnLogWritesTheSameLinesAsBefore() throws Exception {
		PersistenceLog.configure(true, folder.toFile());
		World world = mock(World.class);
		Chunk chunk = mock(Chunk.class);
		when(world.getName()).thenReturn("harbour");
		when(chunk.getX()).thenReturn(2);
		when(chunk.getZ()).thenReturn(-3);
		when(chunk.isLoaded()).thenReturn(true);
		when(world.getChunkAt(any(Location.class))).thenReturn(chunk);
		Location before = new Location(world, 32, 64, -48);
		Location target = new Location(world, 32.5, 64.5, -48, 90, 0);
		String uuid = "golden-persistence-" + UUID.randomUUID();
		ActiveVehicle vehicle = vehicle(uuid, new Location(world, 32.5, 64.5, -48, 90, 0));
		int cycle = PersistenceLog.spawnCycle() + 1;

		PersistenceLog.spawnManagerStart(4);
		PersistenceLog.placeCars(vehicle);
		PersistenceLog.applyPose(vehicle, "spline", before, target);
		PersistenceLog.particleOffset(vehicle, before, target);
		PersistenceLog.spawnLoad("cart", before);
		PersistenceLog.unload(vehicle, "unload");

		String at = "harbour 32.500,64.500,-48.000 yaw=90.000 pitch=0.000 chunk=2,-3 loaded=true";
		String was = "harbour 32.000,64.000,-48.000 yaw=0.000 pitch=0.000 chunk=2,-3 loaded=true";
		String car = "uuid=" + uuid + " id=wagon name=Wagon ageMs=N entity=" + at + " dead=false train=true"
				+ " bound=true spline=null s=60.000 sign=1 parent=null child=pending-child"
				+ " liveParent=false liveChild=false vfPassengers=0 bukkitPassengers=0";
		assertEquals(List.of(
				"SPAWN_MANAGER_START cycle=" + cycle + " pending=4",
				"PLACE_CARS n=1 " + car,
				"APPLY_POSE n=1 reason=spline gap=0.707 before=" + was + " target=" + at + " after=" + at + " " + car,
				"SMOKE_OFFSET gap=0.707 bone=" + was + " entity=" + at + " " + car,
				"SPAWN_LOAD file=cart loc=" + was + " cycle=" + cycle,
				"UNLOAD reason=unload " + car),
				lines());
	}

	private static ActiveVehicle vehicle(String uuid, Location at) {
		ActiveVehicle vehicle = mock(ActiveVehicle.class);
		TrainHandler train = mock(TrainHandler.class);
		SeatHandler seats = mock(SeatHandler.class);
		Entity entity = mock(Entity.class);
		when(vehicle.getUUID()).thenReturn(uuid);
		when(vehicle.getId()).thenReturn("wagon");
		when(vehicle.getName()).thenReturn("Wagon");
		when(vehicle.getSpawnTime()).thenReturn(System.currentTimeMillis());
		when(vehicle.getEntity()).thenReturn(entity);
		when(vehicle.isTrain()).thenReturn(true);
		when(vehicle.getTrainHandler()).thenReturn(train);
		when(vehicle.getSeatHandler()).thenReturn(seats);
		when(seats.getPassengers()).thenReturn(List.of());
		when(entity.getLocation()).thenReturn(at);
		when(entity.getPassengers()).thenReturn(List.of());
		when(train.isBound()).thenReturn(true);
		when(train.getS()).thenReturn(60.0);
		when(train.getTravelSign()).thenReturn(1);
		when(train.getPendingChild()).thenReturn("pending-child");
		return vehicle;
	}

	// Every line but the SESSION header, without its timestamp or the vehicle's age.
	private List<String> lines() throws Exception {
		return Files.readAllLines(folder.resolve("logs/persistence.log")).stream()
				.map(line -> line.substring("yyyy-MM-dd HH:mm:ss ".length()))
				.filter(line -> !line.startsWith("SESSION "))
				.map(line -> line.replaceAll("ageMs=\\d+", "ageMs=N"))
				.toList();
	}
}
