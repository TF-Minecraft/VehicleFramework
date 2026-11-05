package net.tfminecraft.vehicleframework.tracks;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import net.tfminecraft.vehicleframework.VFLogger;
import net.tfminecraft.vehicleframework.VehicleFramework;
import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.data.OwnerData;
import net.tfminecraft.vehicleframework.database.ConsistData;
import net.tfminecraft.vehicleframework.database.VehicleRepository;
import net.tfminecraft.vehicleframework.database.VehicleSnapshot;
import net.tfminecraft.vehicleframework.enums.Component;
import net.tfminecraft.vehicleframework.managers.SpawnManager;
import net.tfminecraft.vehicleframework.managers.VehicleManager;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.component.Engine;
import net.tfminecraft.vehicleframework.vehicles.component.GearedEngine;
import net.tfminecraft.vehicleframework.vehicles.component.fuel.FuelTank;
import net.tfminecraft.vehicleframework.vehicles.fuel.Fuel;
import net.tfminecraft.vehicleframework.vehicles.handlers.SeatHandler;
import net.tfminecraft.vehicleframework.vehicles.handlers.TrainHandler;
import net.tfminecraft.vehicleframework.vehicles.handlers.container.Container;
import net.tfminecraft.vehicleframework.vehicles.handlers.container.ContainerHandler;

class TrainGhostsTest {
	@TempDir Path directory;
	private final List<AutoCloseable> scopes = new ArrayList<>();
	private final World world = mock(World.class);
	private final List<Player> players = new ArrayList<>();
	private final HashMap<Entity, ActiveVehicle> loaded = new HashMap<>();
	private TrackRegistry registry;
	private TrackStore store;
	private TrackSpline line;
	private VehicleRepository repository;
	private VehicleManager vehicles;
	private SpawnManager spawns;
	private MockedStatic<VehicleFramework> framework;
	private MockedStatic<Bukkit> bukkit;
	private MockedStatic<VFLogger> logger;
	private boolean runUnloaded;
	private int saveSeconds, despawn;
	private double offset;

	@BeforeEach
	void setup() {
		runUnloaded = Cache.trainRunUnloaded;
		saveSeconds = Cache.trainGhostSaveSeconds;
		despawn = Cache.despawnDistance;
		offset = Cache.trackVehicleYOffset;
		Cache.trainRunUnloaded = true;
		Cache.trainGhostSaveSeconds = 30;
		Cache.despawnDistance = 50 * 50;
		Cache.trackVehicleYOffset = 0.5;
		registry = new TrackRegistry(directory.toFile());
		store = new TrackStore(directory.toFile());
		line = TrackSpline.fromPoints(UUID.randomUUID(), "world", false,
				List.of(new double[] {0, 64, 0}, new double[] {0, 64, 400}));
		store.save(line);
		registry.loadFromDisk();
		repository = VehicleRepository.open(directory.resolve("cars.db").toFile());
		vehicles = mock(VehicleManager.class);
		spawns = mock(SpawnManager.class);
		when(vehicles.get()).thenReturn(loaded);
		when(vehicles.getSpawnManager()).thenReturn(spawns);
		framework = keep(mockStatic(VehicleFramework.class));
		framework.when(VehicleFramework::getTrackRegistry).thenReturn(registry);
		framework.when(VehicleFramework::getVehicleRepository).thenReturn(repository);
		framework.when(VehicleFramework::getVehicleManager).thenReturn(vehicles);
		bukkit = keep(mockStatic(Bukkit.class));
		bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
		logger = keep(mockStatic(VFLogger.class));
		when(world.getName()).thenReturn("world");
		when(world.getPlayers()).thenReturn(players);
		when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
		Block air = mock(Block.class);
		when(air.isPassable()).thenReturn(true);
		when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(air);
		TrainGhosts.restore();
	}

	@AfterEach
	void cleanup() throws Exception {
		for (GhostTrain ghost : TrainGhosts.all()) {
			TrainGhosts.unregister(ghost);
		}
		while (TrainGhosts.consumeTrusted("loco") || TrainGhosts.consumeTrusted("car")) {
			// forget trusted cars between tests
		}
		Cache.trainRunUnloaded = runUnloaded;
		Cache.trainGhostSaveSeconds = saveSeconds;
		Cache.despawnDistance = despawn;
		Cache.trackVehicleYOffset = offset;
		java.util.Collections.reverse(scopes);
		for (AutoCloseable scope : scopes) {
			scope.close();
		}
		repository.close();
	}

	private <T extends AutoCloseable> T keep(T scope) {
		scopes.add(scope);
		return scope;
	}

	private String id() {
		return line.getId().toString();
	}

	// Out along the line 10 blocks a second, a stop at the far end, and back.
	private ThrottleTape tape() {
		return new ThrottleTape(id(), List.of(
				new ThrottleTape.Sample(100, 1, 100, 20, id(), null, 1),
				new ThrottleTape.Sample(110, 1, 100, 20, id(), null, 1),
				new ThrottleTape.Sample(120, 1, 0, 60, id(), null, 1),
				new ThrottleTape.Sample(120, -1, -100, 20, id(), null, 1),
				new ThrottleTape.Sample(110, -1, -100, 20, id(), null, 1)));
	}

	private void row(String uuid, String payload) {
		repository.saveLive(new VehicleSnapshot(uuid, "wagon", "Car", "character:a", "world", 0, 64, 0, 0, 0, 0,
				payload, 2, 1, false, 1));
	}

	private JSONObject payload(String uuid) throws Exception {
		return (JSONObject) new JSONParser().parse(repository.findLive(uuid).orElseThrow().getPayloadJson());
	}

	private GhostTrain ghost(List<String> cars, int cursor, double fuel) {
		double[] offsets = new double[cars.size()];
		double[] reaches = new double[cars.size()];
		for (int i = 0; i < cars.size(); i++) {
			offsets[i] = 8 * i;
			reaches[i] = 3;
		}
		return new GhostTrain("world", cars, cars.stream().map(car -> "character:a").toList(), offsets, reaches,
				tape(), new TapeTimetable.Clock(cursor, 0), Map.of(), fuel, true, 1, false, 100, null, 0, 0);
	}

	private Player player(double z) {
		Player player = mock(Player.class);
		when(player.getLocation()).thenReturn(new Location(world, 0, 64, z));
		players.add(player);
		return player;
	}

	@Test
	void aRunMovesAlongItsTimetableSavingWhereItIsAndSpawnsWhenAPlayerComesNear() throws Exception {
		row("loco", "{\"id\":\"wagon\",\"child\":\"car\",\"components\":{\"engine\":{\"fuel\":5.0,\"throttle\":60}}}");
		row("car", "{\"id\":\"wagon\",\"parent\":\"loco\"}");
		GhostTrain ghost = ghost(List.of("loco", "car"), 0, 50);
		TrainGhosts.register(ghost);
		ghost.sinceSave = 0;
		assertTrue(TrainGhosts.isGhost("LOCO"));
		assertTrue(TrainGhosts.running(ghost));
		assertFalse(TrainGhosts.running(null));
		assertEquals(1, TrainGhosts.count());
		Cache.trainGhostSaveSeconds = 2;
		TrainGhosts.tick();
		assertEquals(new TapeTimetable.Clock(0, 20), ghost.clock);
		assertEquals(49, ghost.fuel, 1e-9);
		TrainGhosts.tick();
		// Saved after two seconds: the rows hold the run and where the cars are.
		JSONObject loco = payload("loco");
		assertTrue(loco.containsKey(TrainGhosts.KEY));
		assertEquals(120.0, ConsistData.fromJson(loco).getS(), 1e-9);
		assertEquals("car", ConsistData.fromJson(loco).getChild());
		assertEquals(112.0, ConsistData.fromJson(payload("car")).getS(), 1e-9);
		assertEquals(120.0, repository.findLive("loco").orElseThrow().getZ(), 1e-6);
		assertEquals(64.5, repository.findLive("loco").orElseThrow().getY(), 1e-6);
		assertEquals(1L, loco.get("tapeCursor"));
		// The saved cars are not spawned from their rows while the run is on paper.
		assertTrue(TrainGhosts.isGhost("car"));
		// A player comes near: the cars are written where they are and spawned there.
		player(400);
		player(150);
		when(spawns.spawnNow(anyString())).thenReturn(true);
		TrainGhosts.tick();
		verify(spawns).spawnNow("loco");
		verify(spawns).spawnNow("car");
		assertFalse(TrainGhosts.isGhost("loco"));
		assertFalse(payload("loco").containsKey(TrainGhosts.KEY));
		assertEquals(0L, ((JSONObject) ((JSONObject) payload("loco").get("components")).get("engine")).get("throttle"));
		assertTrue(TrainGhosts.consumeTrusted("Loco"));
		assertFalse(TrainGhosts.consumeTrusted("loco"));
		assertFalse(TrainGhosts.consumeTrusted(null));
		assertFalse(TrainGhosts.isGhost(null));
	}

	@Test
	void itWaitsWhereItWouldAppearInsideSomethingAndKeepsRunningWhenTheAreaIsNotLoaded() {
		row("loco", "{\"id\":\"wagon\"}");
		GhostTrain ghost = ghost(List.of("loco"), 0, 50);
		TrainGhosts.register(ghost);
		player(105);
		// Chunks round it are not loaded yet: it runs on.
		when(world.isChunkLoaded(0, 8)).thenReturn(false);
		TrainGhosts.tick();
		assertEquals(new TapeTimetable.Clock(0, 20), ghost.clock);
		when(world.isChunkLoaded(0, 8)).thenReturn(true);
		when(world.isChunkLoaded(0, 6)).thenReturn(false);
		TrainGhosts.tick();
		assertEquals(new TapeTimetable.Clock(1, 20), ghost.clock);
		when(world.isChunkLoaded(0, 6)).thenReturn(true);
		// Another train stands on the spot: it waits there.
		TrainHandler other = mock(TrainHandler.class);
		when(other.isBound()).thenReturn(true);
		when(other.getSplineId()).thenReturn(line.getId());
		when(other.getS()).thenReturn(121.0);
		when(other.trackReach()).thenReturn(2.0);
		ActiveVehicle train = mock(ActiveVehicle.class);
		when(train.getUUID()).thenReturn("other");
		when(train.isTrain()).thenReturn(true);
		when(train.getTrainHandler()).thenReturn(other);
		ActiveVehicle cart = mock(ActiveVehicle.class);
		when(cart.getUUID()).thenReturn("cart");
		loaded.put(mock(Entity.class), train);
		loaded.put(mock(Entity.class), cart);
		loaded.put(mock(Entity.class), mock(ActiveVehicle.class));
		TrainGhosts.tick();
		assertEquals(new TapeTimetable.Clock(1, 20), ghost.clock);
		verify(spawns, never()).spawnNow(anyString());
		// It moves off, or something is in the way.
		when(other.getS()).thenReturn(300.0);
		Block stone = mock(Block.class);
		when(world.getBlockAt(0, 65, 120)).thenReturn(stone);
		org.bukkit.util.VoxelShape shape = mock(org.bukkit.util.VoxelShape.class);
		when(shape.getBoundingBoxes()).thenReturn(List.of(new org.bukkit.util.BoundingBox(0, 0, 0, 1, 1, 1)));
		when(stone.getCollisionShape()).thenReturn(shape);
		TrainGhosts.tick();
		verify(spawns, never()).spawnNow(anyString());
		when(stone.isPassable()).thenReturn(true);
		TrainGhosts.tick();
		verify(spawns).spawnNow("loco");
		// It did not spawn after all: when it does, it checks its track like any saved train.
		assertFalse(TrainGhosts.consumeTrusted("loco"));
	}

	@Test
	void parkedCarsAndOtherRunsOnTheSameRailsBlockItsSpot() {
		row("loco", "{\"id\":\"wagon\"}");
		row("parked", "{\"id\":\"wagon\",\"splineId\":\"" + id() + "\",\"s\":101.0,\"travelSign\":1}");
		row("elsewhere", "{\"id\":\"wagon\",\"splineId\":\"not-a-uuid\",\"s\":101.0,\"travelSign\":1}");
		row("far", "{\"id\":\"wagon\",\"splineId\":\"" + id() + "\",\"s\":300.0,\"travelSign\":1}");
		GhostTrain ghost = ghost(List.of("loco"), 0, 50);
		TrainGhosts.register(ghost);
		player(100);
		TrainGhosts.tick();
		verify(spawns, never()).spawnNow(anyString());
		repository.tombstone("parked");
		ghost.clock = new TapeTimetable.Clock(0, 0);
		assertFalse(TrainGhosts.blocked(world, ghost, ghost.place(registry), registry));
		// Another run on paper passes through it, and its saved rows do not count.
		row("other", "{\"id\":\"wagon\",\"splineId\":\"" + id() + "\",\"s\":100.0,\"travelSign\":1}");
		GhostTrain other = ghost(List.of("other"), 0, 50);
		TrainGhosts.register(other);
		assertFalse(TrainGhosts.blocked(world, ghost, ghost.place(registry), registry));
		framework.when(VehicleFramework::getVehicleRepository).thenReturn(null);
		assertFalse(TrainGhosts.blocked(world, ghost, ghost.place(registry), registry));
		// No world.
		bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(null);
		assertEquals(TrainGhosts.Spawn.NOT_READY, TrainGhosts.materialize(ghost, ghost.spots, registry));
		assertFalse(TrainGhosts.nearPlayer(ghost, ghost.spots));
		bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
		// A saved car on a track the registry has lost still blocks a place on that id.
		framework.when(VehicleFramework::getVehicleRepository).thenReturn(repository);
		UUID gone = UUID.randomUUID();
		GhostTrain.Spot lost = new GhostTrain.Spot(gone, 5, 1, 1, line.sampleAt(5));
		GhostTrain lone = ghost(List.of("lone"), 0, 50);
		assertFalse(TrainGhosts.blocked(world, lone, List.of(lost), registry));
		row("stray", "{\"id\":\"wagon\",\"splineId\":\"" + gone + "\",\"s\":6.0,\"travelSign\":1}");
		assertTrue(TrainGhosts.blocked(world, lone, List.of(lost), registry));
		repository.tombstone("stray");
		// Spawned where the track is gone: the cars check their track when they load.
		when(spawns.spawnNow(anyString())).thenReturn(true);
		assertEquals(TrainGhosts.Spawn.SPAWNED, TrainGhosts.materialize(ghost, List.of(lost), registry));
		assertFalse(TrainGhosts.consumeTrusted("loco"));
	}

	@Test
	void savingWithoutTheDatabaseOrWithTheLocomotiveGoneEndsOrSkips() {
		GhostTrain ghost = ghost(List.of("loco", "car"), 0, 50);
		TrainGhosts.register(ghost);
		framework.when(VehicleFramework::getVehicleRepository).thenReturn(null);
		assertFalse(TrainGhosts.save(ghost, false));
		framework.when(VehicleFramework::getVehicleRepository).thenReturn(repository);
		row("car", "{\"id\":\"wagon\"}");
		assertFalse(TrainGhosts.save(ghost, false));
		assertFalse(TrainGhosts.running(ghost));
		logger.verify(() -> VFLogger.log(contains("its locomotive was removed")));
		// A car that is gone is skipped; a failing database is logged.
		row("loco", "{\"id\":\"wagon\"}");
		GhostTrain again = ghost(List.of("loco", "gone"), 0, 50);
		assertTrue(TrainGhosts.save(again, true));
		VehicleRepository broken = mock(VehicleRepository.class);
		doThrow(new IllegalStateException("disk full")).when(broken).runInTransaction(any());
		framework.when(VehicleFramework::getVehicleRepository).thenReturn(broken);
		again.consumed = 3;
		assertFalse(TrainGhosts.save(again, false));
		// Rolled back: the fuel used is still owed by the fuel car.
		assertEquals(3, again.consumed);
		logger.verify(() -> VFLogger.log(contains("disk full")));
		TrainGhosts.register(again);
		assertEquals(TrainGhosts.Spawn.NOT_READY, TrainGhosts.materialize(again, again.place(registry), registry));
		// A track that is gone keeps the last places.
		again.spots = List.of();
		framework.when(VehicleFramework::getVehicleRepository).thenReturn(repository);
		GhostTrain stray = new GhostTrain("world", List.of("loco"), List.of("a"), new double[] {0}, new double[] {3},
				new ThrottleTape("nowhere", List.of(new ThrottleTape.Sample(0, 1, 50))), new TapeTimetable.Clock(0, 0),
				Map.of(), 0, false, 0, false, 0, null, 0, 0);
		assertTrue(TrainGhosts.save(stray, false));
		TrainGhosts.register(stray);
		// Its track is gone: it stops running on paper and its cars load from their rows.
		framework.when(VehicleFramework::getVehicleRepository).thenReturn(null);
		TrainGhosts.tick();
		assertTrue(TrainGhosts.running(stray));
		framework.when(VehicleFramework::getVehicleRepository).thenReturn(repository);
		TrainGhosts.tick();
		assertFalse(TrainGhosts.running(stray));
		verify(spawns).requeue("loco");
	}

	@Test
	void fuelTheEngineUsedOnPaperLeavesTheFuelCar() {
		row("loco", "{\"id\":\"wagon\"}");
		row("tender", "{\"id\":\"wagon\"}");
		GhostTrain ghost = new GhostTrain("world", List.of("loco", "tender"), List.of("a", "a"),
				new double[] {0, 8}, new double[] {3, 3}, tape(), new TapeTimetable.Clock(0, 0), Map.of(), 0, true,
				1, false, 100, "coal", 10, 5);
		ghost.consumed = 2;
		MockedStatic<StoredCars> stored = mockStatic(StoredCars.class, CALLS_REAL_METHODS);
		try {
			org.bukkit.inventory.ItemStack coal = mock(org.bukkit.inventory.ItemStack.class);
			Container hold = mock(Container.class);
			when(hold.takeOneMatching("coal")).thenReturn(coal, (org.bukkit.inventory.ItemStack) null);
			Container spare = mock(Container.class);
			when(spare.takeOneMatching("coal")).thenReturn(coal);
			stored.when(() -> StoredCars.containers(any())).thenReturn(List.of(hold, spare));
			stored.when(() -> StoredCars.putContainers(any(), any())).thenAnswer(call -> null);
			TrainGhosts.flushFuel(repository, ghost);
			verify(hold, times(2)).takeOneMatching("coal");
			verify(spare).takeOneMatching("coal");
			assertEquals(0, ghost.consumed);
			// Nothing used, no fuel car, or a fuel car whose row is gone.
			TrainGhosts.flushFuel(repository, ghost);
			ghost.consumed = 1;
			repository.tombstone("tender");
			TrainGhosts.flushFuel(repository, ghost);
			assertEquals(1, ghost.consumed);
			GhostTrain single = ghost(List.of("loco"), 0, 50);
			single.consumed = 3;
			TrainGhosts.flushFuel(repository, single);
			stored.verify(() -> StoredCars.containers(any()), times(1));
		} finally {
			stored.close();
		}
	}

	@Test
	void runsOnPaperResumeAfterARestartAndSaveOnShutdown() throws Exception {
		row("loco", "{\"id\":\"wagon\"}");
		row("car", "{\"id\":\"wagon\"}");
		GhostTrain ghost = ghost(List.of("loco", "car"), 2, 40);
		ghost.clock = new TapeTimetable.Clock(2, 15);
		TrainGhosts.register(ghost);
		TrainGhosts.saveAll();
		JSONObject saved = payload("loco");
		saved.put("throttleTape", tape().toJson());
		repository.saveLive(new VehicleSnapshot("loco", "wagon", "Car", "character:a", "world", 0, 64, 0, 0, 0, 0,
				saved.toJSONString(), 2, 1, false, 1));
		row("mismatch", saved.toJSONString());
		row("notape", "{\"id\":\"wagon\",\"" + TrainGhosts.KEY + "\":{}}");
		row("broken", "{\"" + TrainGhosts.KEY + "\":5}");
		TrainGhosts.restore();
		assertEquals(1, TrainGhosts.count());
		GhostTrain resumed = TrainGhosts.all().get(0);
		assertEquals(new TapeTimetable.Clock(2, 15), resumed.clock);
		assertEquals(40, resumed.fuel, 1e-9);
		assertTrue(TrainGhosts.isGhost("car"));
		logger.verify(() -> VFLogger.log(contains("Could not resume train broken")));
		logger.verify(() -> VFLogger.info("Resumed 1 train(s) running unloaded"));
		framework.when(VehicleFramework::getVehicleRepository).thenReturn(null);
		TrainGhosts.restore();
		assertEquals(0, TrainGhosts.count());
	}

	@Test
	void aStepThatFailsIsLoggedAndTheOthersRunOn() {
		TrainGhosts.tick();
		GhostTrain broken = ghost(List.of("loco"), 0, 50);
		TrainGhosts.register(broken);
		GhostTrain other = ghost(List.of("other"), 0, 50);
		TrainGhosts.register(other);
		when(world.getPlayers()).thenReturn(null);
		TrainGhosts.tick();
		logger.verify(() -> VFLogger.log(contains("running unloaded failed a step")), times(2));
	}

	@Test
	void arrivingAtARecordedStopServesItsDocks() {
		row("loco", "{\"id\":\"wagon\"}");
		GhostTrain ghost = ghost(List.of("loco"), 1, 50);
		ghost.clock = new TapeTimetable.Clock(1, 10);
		TrainGhosts.register(ghost);
		MockedStatic<CargoDocks> docks = mockStatic(CargoDocks.class);
		try {
			TrainGhosts.tick();
			docks.verify(() -> CargoDocks.servePaper(ghost, 2));
			assertEquals(1, ghost.stopSerial);
		} finally {
			docks.close();
		}
	}

	// A live train ready to go on paper: locomotive and one car with a fuel tank and tape.
	private record Live(ActiveVehicle loco, ActiveVehicle car, TrainHandler train, FuelTank tank, SeatHandler seats) {
	}

	private Live live(boolean gearedEngine) {
		ActiveVehicle loco = mock(ActiveVehicle.class);
		ActiveVehicle car = mock(ActiveVehicle.class);
		TrainHandler train = mock(TrainHandler.class);
		TrainHandler carTrain = mock(TrainHandler.class);
		SeatHandler seats = mock(SeatHandler.class);
		Entity entity = mock(Entity.class);
		when(entity.getWorld()).thenReturn(world);
		Fuel fuel = mock(Fuel.class);
		when(fuel.getItem()).thenReturn("coal");
		when(fuel.getAmount()).thenReturn(10);
		FuelTank tank = new FuelTank(30, 100, 2, List.of(), fuel);
		for (ActiveVehicle vehicle : List.of(loco, car)) {
			when(vehicle.isTrain()).thenReturn(true);
			when(vehicle.getEntity()).thenReturn(entity);
			when(vehicle.getSeatHandler()).thenReturn(seats);
			OwnerData owner = mock(OwnerData.class);
			when(owner.getOwner()).thenReturn("character:a");
			when(vehicle.getOwnerData()).thenReturn(vehicle == loco ? owner : null);
		}
		when(loco.getUUID()).thenReturn("LOCO");
		when(car.getUUID()).thenReturn("car");
		when(loco.getTrainHandler()).thenReturn(train);
		when(car.getTrainHandler()).thenReturn(carTrain);
		when(car.hasParent()).thenReturn(true);
		when(car.getParent()).thenReturn(loco);
		when(loco.isLocomotive()).thenReturn(true);
		if (gearedEngine) {
			GearedEngine engine = mock(GearedEngine.class);
			when(engine.getFuelTank()).thenReturn(tank);
			when(loco.hasComponent(Component.GEARED_ENGINE)).thenReturn(true);
			when(loco.getComponent(Component.GEARED_ENGINE)).thenReturn(engine);
		} else {
			Engine engine = mock(Engine.class);
			when(engine.getFuelTank()).thenReturn(tank);
			when(loco.hasComponent(Component.ENGINE)).thenReturn(true);
			when(loco.getComponent(Component.ENGINE)).thenReturn(engine);
		}
		when(train.isBound()).thenReturn(true);
		when(carTrain.isBound()).thenReturn(true);
		when(train.hasInstalledTape()).thenReturn(true);
		when(train.getInstalledTape()).thenReturn(tape());
		when(train.getTapeClock()).thenReturn(new int[] {1, 4});
		when(train.getSplineId()).thenReturn(line.getId());
		when(train.getS()).thenReturn(112.0);
		when(train.getTravelSign()).thenReturn(1);
		when(train.layout()).thenReturn(new TrainHandler.Layout(List.of(loco, car), new double[] {0, 8},
				new double[] {3, 3}, Map.of()));
		when(vehicles.getByUUID("loco")).thenReturn(loco);
		when(vehicles.unload(any(), anyString())).thenReturn(true);
		return new Live(loco, car, train, tank, seats);
	}

	@Test
	void aTapeTrainWhoseChunkUnloadsGoesOnPaperAsAWhole() throws Exception {
		row("loco", "{\"id\":\"wagon\",\"components\":{\"engine\":{\"fuel\":30.0}}}");
		row("car", "{\"id\":\"wagon\"}");
		Live live = live(false);
		// The fuel car behind it: its coal is the reserve the engine draws on.
		when(live.train.acceptsFuelCar(live.car)).thenReturn(true);
		when(live.car.hasContainers()).thenReturn(true);
		ContainerHandler handler = mock(ContainerHandler.class);
		Container hold = mock(Container.class);
		when(hold.countMatching("coal")).thenReturn(7);
		when(handler.getContainers()).thenReturn(new HashMap<>(Map.of("seat", hold)));
		when(live.car.getContainerHandler()).thenReturn(handler);
		assertTrue(TrainGhosts.tryConvert(live.car, "unload"));
		verify(vehicles).unload(live.loco, "going on paper");
		verify(vehicles).unload(live.car, "going on paper");
		GhostTrain ghost = TrainGhosts.all().get(0);
		assertEquals(List.of("loco", "car"), ghost.cars());
		assertEquals(List.of("character:a", "none"), ghost.owners);
		assertEquals(new TapeTimetable.Clock(1, 4), ghost.clock);
		assertEquals(30, ghost.fuel, 1e-9);
		assertEquals(7, ghost.reserve);
		assertEquals("coal", ghost.fuelPath);
		assertTrue(payload("loco").containsKey(TrainGhosts.KEY));
		// Already on paper: not again.
		assertFalse(TrainGhosts.tryConvert(live.loco, "unload"));
	}

	@Test
	void aTrainAtTheLoadedEdgeAsksAndGoesOnPaperAfterTheTick() {
		row("loco", "{\"id\":\"wagon\"}");
		row("car", "{\"id\":\"wagon\"}");
		Live live = live(true);
		when(live.train.getTapeClock()).thenReturn(new int[] {-1, 0});
		TrainGhosts.flush(vehicles);
		TrainGhosts.request(null);
		ActiveVehicle nameless = mock(ActiveVehicle.class);
		TrainGhosts.request(nameless);
		TrainGhosts.request(live.loco);
		ActiveVehicle missing = mock(ActiveVehicle.class);
		when(missing.getUUID()).thenReturn("missing");
		TrainGhosts.request(missing);
		TrainGhosts.flush(vehicles);
		assertEquals(1, TrainGhosts.count());
		// Its cursor was unknown: it starts from the sample nearest where it is.
		assertEquals(1, TrainGhosts.all().get(0).clock.cursor());
		assertEquals(0, TrainGhosts.all().get(0).reserve);
		TrainGhosts.flush(vehicles);
	}

	@Test
	void aRunOutOfFuelOrOnAnEditedTrackStopsRunningOnPaper() {
		row("loco", "{\"id\":\"wagon\"}");
		GhostTrain dry = ghost(List.of("loco"), 0, 0);
		TrainGhosts.register(dry);
		TrainGhosts.tick();
		assertFalse(TrainGhosts.running(dry));
		verify(spawns).requeue("loco");
		// A track edit ends the runs whose tape or cars use it; others run on.
		row("a", "{\"id\":\"wagon\"}");
		row("b", "{\"id\":\"wagon\"}");
		GhostTrain onIt = ghost(List.of("a"), 0, 50);
		GhostTrain standing = new GhostTrain("world", List.of("b"), List.of("x"), new double[] {0}, new double[] {3},
				new ThrottleTape("other", List.of(new ThrottleTape.Sample(0, 1, 0))), new TapeTimetable.Clock(0, 0),
				Map.of(), 0, false, 0, false, 0, null, 0, 0);
		standing.spots = List.of(new GhostTrain.Spot(line.getId(), 5, 1, 1, line.sampleAt(5)));
		GhostTrain elsewhere = new GhostTrain("world", List.of("c"), List.of("x"), new double[] {0}, new double[] {3},
				new ThrottleTape("other", List.of(new ThrottleTape.Sample(0, 1, 0))), new TapeTimetable.Clock(0, 0),
				Map.of(), 0, false, 0, false, 0, null, 0, 0);
		TrainGhosts.register(onIt);
		TrainGhosts.register(standing);
		TrainGhosts.register(elsewhere);
		assertTrue(TrainGhosts.onTrack(line.getId()));
		assertFalse(TrainGhosts.onTrack(UUID.randomUUID()));
		assertTrue(TrainHandler.anyTrainOn(line.getId()));
		TrainHandler.retrackTrains(line, List.of());
		assertFalse(TrainGhosts.running(onIt));
		assertFalse(TrainGhosts.running(standing));
		assertTrue(TrainGhosts.running(elsewhere));
		TrainHandler.retrackTrains(null, List.of());
	}

	@Test
	void aTrainWithAStorageWindowOpenStaysLoaded() {
		Live live = live(false);
		when(live.car.hasContainers()).thenReturn(true);
		ContainerHandler handler = mock(ContainerHandler.class);
		Container closed = mock(Container.class);
		Container open = mock(Container.class);
		when(open.hasViewers()).thenReturn(true);
		when(handler.getContainers()).thenReturn(new HashMap<>(Map.of("a", closed)));
		when(live.car.getContainerHandler()).thenReturn(handler);
		assertTrue(TrainGhosts.eligible(live.loco));
		when(handler.getContainers()).thenReturn(new HashMap<>(Map.of("a", closed, "b", open)));
		assertFalse(TrainGhosts.eligible(live.loco));
	}

	@Test
	void tapeTrainsStillLoadedAtShutdownGoOnPaper() {
		row("loco", "{\"id\":\"wagon\"}");
		row("car", "{\"id\":\"wagon\"}");
		Live live = live(false);
		ActiveVehicle cart = mock(ActiveVehicle.class);
		loaded.put(mock(Entity.class), cart);
		loaded.put(mock(Entity.class), live.car);
		loaded.put(mock(Entity.class), live.loco);
		TrainGhosts.convertAll(vehicles);
		assertEquals(1, TrainGhosts.count());
		verify(vehicles).unload(live.loco, "going on paper");
	}

	@Test
	void onlyARunningTapeTrainWithNobodyAboardGoesOnPaper() {
		Live live = live(false);
		assertTrue(TrainGhosts.eligible(live.loco));
		Cache.trainRunUnloaded = false;
		assertFalse(TrainGhosts.eligible(live.loco));
		Cache.trainRunUnloaded = true;
		assertFalse(TrainGhosts.eligible(null));
		when(live.seats.hasPassengers()).thenReturn(true);
		assertFalse(TrainGhosts.tryConvert(live.loco, "edge"));
		when(live.seats.hasPassengers()).thenReturn(false);
		when(live.car.getTrainHandler().isBound()).thenReturn(false);
		assertFalse(TrainGhosts.eligible(live.loco));
		when(live.car.getTrainHandler().isBound()).thenReturn(true);
		when(live.car.isDestroyed()).thenReturn(true);
		assertFalse(TrainGhosts.eligible(live.loco));
		when(live.car.isDestroyed()).thenReturn(false);
		Entity elsewhere = mock(Entity.class);
		when(elsewhere.getWorld()).thenReturn(mock(World.class));
		when(live.car.getEntity()).thenReturn(elsewhere);
		assertFalse(TrainGhosts.eligible(live.loco));
		when(live.car.getEntity()).thenReturn(null);
		assertFalse(TrainGhosts.eligible(live.loco));
		Entity body = live.loco.getEntity();
		when(live.car.getEntity()).thenReturn(body);
		live.tank.setFuel(0);
		assertFalse(TrainGhosts.eligible(live.loco));
		live.tank.setFuel(10);
		when(live.train.hasMissingCars()).thenReturn(true);
		assertFalse(TrainGhosts.eligible(live.loco));
		when(live.train.hasMissingCars()).thenReturn(false);
		when(live.train.isRecording()).thenReturn(true);
		assertFalse(TrainGhosts.eligible(live.loco));
		when(live.train.isRecording()).thenReturn(false);
		when(live.train.hasInstalledTape()).thenReturn(false);
		assertFalse(TrainGhosts.eligible(live.loco));
		when(live.train.hasInstalledTape()).thenReturn(true);
		when(live.train.isBound()).thenReturn(false);
		assertFalse(TrainGhosts.eligible(live.loco));
		when(live.train.isBound()).thenReturn(true);
		when(live.loco.getEntity()).thenReturn(null);
		assertFalse(TrainGhosts.eligible(live.loco));
		when(live.loco.getUUID()).thenReturn(null);
		assertFalse(TrainGhosts.eligible(live.loco));
		when(live.loco.isDestroyed()).thenReturn(true);
		assertFalse(TrainGhosts.eligible(live.loco));
		when(live.loco.isTrain()).thenReturn(false);
		assertFalse(TrainGhosts.eligible(live.loco));
		// A train with no engine needs no fuel.
		Live plain = live(false);
		when(plain.loco.hasComponent(Component.ENGINE)).thenReturn(false);
		assertNull(TrainGhosts.tank(plain.loco));
		assertTrue(TrainGhosts.eligible(plain.loco));
	}

	@Test
	void aTrainThatCannotBeSavedOrPlacedStaysLoaded() {
		Live live = live(false);
		when(vehicles.unload(live.car, "going on paper")).thenReturn(false);
		assertFalse(TrainGhosts.tryConvert(live.loco, "unload"));
		assertEquals(0, TrainGhosts.count());
		logger.verify(() -> VFLogger.log(contains("does not run unloaded")));
		// The locomotive was already saved and removed: it spawns again from its row.
		verify(spawns).requeue("loco");
		// Its track is gone and its cursor unknown: no place on the tape.
		when(live.train.getTapeClock()).thenReturn(new int[] {-1, 0});
		when(live.train.getSplineId()).thenReturn(UUID.randomUUID());
		assertNull(TrainGhosts.capture(live.loco, live.train.layout()));
		when(live.train.getSplineId()).thenReturn(line.getId());
		when(live.train.getS()).thenReturn(390.0);
		assertNull(TrainGhosts.capture(live.loco, live.train.layout()));
		assertFalse(TrainGhosts.tryConvert(live.loco, "unload"));
		when(live.train.getTapeClock()).thenReturn(new int[] {99, 0});
		assertNull(TrainGhosts.capture(live.loco, live.train.layout()));
		// A cursor left behind when the train was driven elsewhere is not trusted.
		when(live.train.getTapeClock()).thenReturn(new int[] {1, 4});
		assertNull(TrainGhosts.capture(live.loco, live.train.layout()));
		when(live.train.getS()).thenReturn(121.0);
		assertEquals(2, TrainGhosts.capture(live.loco, live.train.layout()).clock.cursor());
	}
}
