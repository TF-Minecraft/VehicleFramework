package net.tfminecraft.vehicleframework.tracks;

import static net.tfminecraft.vehicleframework.test.FakeItems.count;
import static net.tfminecraft.vehicleframework.test.FakeItems.item;
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
import java.util.concurrent.CompletableFuture;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Barrel;
import org.bukkit.block.Chest;
import org.bukkit.block.Furnace;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.json.simple.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import net.tfminecraft.vehicleframework.VFLogger;
import net.tfminecraft.vehicleframework.VehicleFramework;
import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.data.OwnerData;
import net.tfminecraft.vehicleframework.database.VehicleRepository;
import net.tfminecraft.vehicleframework.database.VehicleSnapshot;
import net.tfminecraft.vehicleframework.ownership.OwnershipAccess;
import net.tfminecraft.vehicleframework.permissions.Permissions;
import net.tfminecraft.vehicleframework.tracks.DockRegistry.Mode;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.handlers.TrainHandler;
import net.tfminecraft.vehicleframework.vehicles.handlers.container.Container;
import net.tfminecraft.vehicleframework.vehicles.handlers.container.ContainerHandler;

class CargoDocksTest {
	@TempDir Path directory;
	private final List<AutoCloseable> scopes = new ArrayList<>();
	private final World world = mock(World.class);
	private final Map<String, Block> blocks = new HashMap<>();
	private MockedStatic<Bukkit> bukkit;
	private MockedStatic<VehicleFramework> framework;
	private boolean enabled, fuelCars;
	private int perStop;
	private double reach;

	@BeforeAll
	static void sounds() {
		net.tfminecraft.vehicleframework.test.RegistryFixture.initialize();
	}

	@BeforeEach
	void setup() {
		enabled = Cache.trainDocksEnabled;
		fuelCars = Cache.trainDockFuelCars;
		perStop = Cache.trainDockItemsPerStop;
		reach = Cache.trainDockReach;
		Cache.trainDocksEnabled = true;
		Cache.trainDockFuelCars = false;
		Cache.trainDockItemsPerStop = 576;
		Cache.trainDockReach = 4;
		keep(mockStatic(VFLogger.class));
		bukkit = keep(mockStatic(Bukkit.class));
		bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
		framework = keep(mockStatic(VehicleFramework.class));
		when(world.getName()).thenReturn("world");
		when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
		when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> blocks.computeIfAbsent(
				call.getArgument(0) + "," + call.getArgument(1) + "," + call.getArgument(2), key -> {
					Block plain = mock(Block.class);
					when(plain.getState(false)).thenReturn(mock(BlockState.class));
					return plain;
				}));
		DockRegistry.load(directory.toFile());
	}

	@AfterEach
	void cleanup() throws Exception {
		for (GhostTrain ghost : TrainGhosts.all()) {
			TrainGhosts.unregister(ghost);
		}
		DockRegistry.load(null);
		Cache.trainDocksEnabled = enabled;
		Cache.trainDockFuelCars = fuelCars;
		Cache.trainDockItemsPerStop = perStop;
		Cache.trainDockReach = reach;
		java.util.Collections.reverse(scopes);
		for (AutoCloseable scope : scopes) {
			scope.close();
		}
	}

	private <T extends AutoCloseable> T keep(T scope) {
		scopes.add(scope);
		return scope;
	}

	/** A storage block at a place, holding these slots. */
	private ItemStack[] storage(int x, int y, int z, Class<? extends BlockState> type, ItemStack... contents) {
		ItemStack[] slots = java.util.Arrays.copyOf(contents, Math.max(contents.length, 9));
		Inventory inventory = mock(Inventory.class);
		when(inventory.getContents()).thenAnswer(call -> slots.clone());
		doAnswer(call -> {
			slots[call.<Integer>getArgument(0)] = call.getArgument(1);
			return null;
		}).when(inventory).setItem(anyInt(), any());
		BlockState state = mock(type);
		if (state instanceof org.bukkit.block.Container container && !(state instanceof Furnace)) {
			when(container.getInventory()).thenReturn(inventory);
		}
		Block block = mock(Block.class);
		when(block.getState(false)).thenReturn(state);
		when(block.getWorld()).thenReturn(world);
		when(block.getX()).thenReturn(x);
		when(block.getY()).thenReturn(y);
		when(block.getZ()).thenReturn(z);
		blocks.put(x + "," + y + "," + z, block);
		return slots;
	}

	/** A car container holding these slots. */
	private Container container(ItemStack[] slots, boolean allowsAll) {
		Container container = mock(Container.class);
		when(container.contents()).thenAnswer(call -> slots.clone());
		doAnswer(call -> {
			ItemStack[] next = call.getArgument(0);
			System.arraycopy(next, 0, slots, 0, slots.length);
			return null;
		}).when(container).setContents(any());
		when(container.allows(any())).thenAnswer(call -> allowsAll
				|| call.<ItemStack>getArgument(0).getType() != Material.DIRT);
		when(container.countMatching("coal")).thenAnswer(call -> count(slots, Material.COAL));
		return container;
	}

	private ActiveVehicle car(double z, String owner, Container... containers) {
		ActiveVehicle car = mock(ActiveVehicle.class);
		Entity entity = mock(Entity.class);
		when(entity.getLocation()).thenReturn(new Location(world, 5.5, 64, z));
		when(car.getEntity()).thenReturn(entity);
		when(car.getUUID()).thenReturn(UUID.randomUUID().toString());
		OwnerData data = mock(OwnerData.class);
		when(data.getOwner()).thenReturn(owner);
		when(car.getOwnerData()).thenReturn(data);
		when(car.hasContainers()).thenReturn(containers.length > 0);
		ContainerHandler handler = mock(ContainerHandler.class);
		HashMap<String, Container> map = new HashMap<>();
		for (int i = 0; i < containers.length; i++) {
			map.put("seat" + i, containers[i]);
		}
		when(handler.getContainers()).thenReturn(map);
		when(car.getContainerHandler()).thenReturn(handler);
		return car;
	}

	private ActiveVehicle train(ActiveVehicle... cars) {
		ActiveVehicle loco = cars[0];
		TrainHandler handler = mock(TrainHandler.class);
		when(handler.layout()).thenReturn(new TrainHandler.Layout(List.of(cars), new double[cars.length],
				new double[cars.length], Map.of()));
		when(loco.getTrainHandler()).thenReturn(handler);
		return loco;
	}

	@Test
	void aLoadDockFillsTheCarsOfItsOwnerUpToTheLimit() {
		ItemStack[] dock = storage(5, 64, 0, Chest.class, item(Material.COAL, 40), item(Material.DIRT, 5),
				item(Material.IRON_INGOT, 30));
		DockRegistry.set("world", 5, 64, 0, Mode.LOAD, "character:a");
		ItemStack[] hold = new ItemStack[2];
		ActiveVehicle loco = car(1, "character:a");
		ActiveVehicle wagon = car(2, "character:a", container(hold, false));
		Cache.trainDockItemsPerStop = 50;
		CargoDocks.serveLive(train(loco, wagon));
		// The container refuses dirt; coal and then iron fill it, 50 items in all.
		assertEquals(40, count(hold, Material.COAL));
		assertEquals(10, count(hold, Material.IRON_INGOT));
		assertEquals(0, count(dock, Material.COAL));
		assertEquals(5, count(dock, Material.DIRT));
		assertEquals(20, count(dock, Material.IRON_INGOT));
	}

	@Test
	void anUnloadDockTakesWhatFitsButNoShulkerBoxIntoAShulkerBox() {
		ItemStack[] dock = storage(5, 64, 0, ShulkerBox.class);
		DockRegistry.set("world", 5, 64, 0, Mode.UNLOAD, "character:a");
		ItemStack[] hold = {item(Material.SHULKER_BOX, 1), item(Material.WHEAT, 20)};
		ActiveVehicle wagon = car(2, "character:a", container(hold, true));
		CargoDocks.serveLive(train(wagon));
		assertEquals(20, count(dock, Material.WHEAT));
		assertEquals(1, count(hold, Material.SHULKER_BOX));
		assertEquals(0, count(hold, Material.WHEAT));
	}

	@Test
	void docksNeverTradeWithOtherOwnersFuelCarsOrUnloadedChunks() {
		ItemStack[] dock = storage(5, 64, 0, Chest.class, item(Material.COAL, 10));
		DockRegistry.set("world", 5, 64, 0, Mode.LOAD, "character:a");
		ItemStack[] hold = new ItemStack[2];
		ActiveVehicle stranger = car(2, "character:b", container(hold, true));
		ActiveVehicle nobody = car(2, "none", container(hold, true));
		ActiveVehicle blank = car(2, " ", container(hold, true));
		ActiveVehicle unowned = car(2, null, container(hold, true));
		ActiveVehicle empty = car(2, "character:a");
		ActiveVehicle gone = car(2, "character:a", container(hold, true));
		when(gone.getEntity()).thenReturn(null);
		ActiveVehicle tender = car(2, "character:a", container(hold, true));
		// A car being destroyed already dropped its cargo.
		ItemStack[] wreckHold = new ItemStack[2];
		ActiveVehicle wreck = car(2, "character:a", container(wreckHold, true));
		when(wreck.isDestroyed()).thenReturn(true);
		ActiveVehicle loco = train(car(1, "character:a"), stranger, nobody, blank, unowned, empty, gone, wreck, tender);
		when(loco.getTrainHandler().acceptsFuelCar(tender)).thenReturn(true);
		CargoDocks.serveLive(loco);
		assertEquals(10, count(dock, Material.COAL));
		Cache.trainDockFuelCars = true;
		when(world.isChunkLoaded(0, 0)).thenReturn(false);
		CargoDocks.serveLive(loco);
		assertEquals(10, count(dock, Material.COAL));
		when(world.isChunkLoaded(0, 0)).thenReturn(true);
		CargoDocks.serveLive(loco);
		assertEquals(0, count(dock, Material.COAL));
		assertEquals(10, count(hold, Material.COAL));
		assertEquals(0, count(wreckHold, Material.COAL));
		// Turned off, no train, or no docks: nothing to do.
		Cache.trainDocksEnabled = false;
		CargoDocks.serveLive(loco);
		Cache.trainDocksEnabled = true;
		CargoDocks.serveLive(null);
		DockRegistry.set("world", 5, 64, 0, null, null);
		CargoDocks.serveLive(loco);
		verify(loco.getTrainHandler(), times(3)).layout();
	}

	@Test
	void aDockWhoseBlockIsNoLongerStorageIsForgotten() {
		DockRegistry.set("world", 5, 64, 0, Mode.LOAD, "character:a");
		ActiveVehicle wagon = car(2, "character:a", container(new ItemStack[2], true));
		CargoDocks.serveLive(train(wagon));
		assertNull(DockRegistry.at("world", 5, 64, 0));
		// Storage of a kind that is not a dock (a furnace) does not count either.
		storage(5, 64, 1, Furnace.class);
		assertNull(CargoDocks.storage(world.getBlockAt(5, 64, 1)));
		// Nothing moved: the dock is left as it was.
		ItemStack[] dock = storage(5, 64, 0, Barrel.class);
		DockRegistry.set("world", 5, 64, 0, Mode.LOAD, "character:a");
		CargoDocks.serveLive(train(wagon));
		assertEquals(0, count(dock, Material.COAL));
	}

	private GhostTrain ghost(String... owners) {
		List<String> cars = new ArrayList<>();
		for (int i = 0; i < owners.length; i++) {
			cars.add("car-" + i);
		}
		GhostTrain ghost = new GhostTrain("world", cars, List.of(owners), new double[owners.length],
				new double[owners.length], new ThrottleTape("t", List.of(new ThrottleTape.Sample(0, 1, 0))),
				new TapeTimetable.Clock(0, 0), Map.of(), 10, true, 1, false, 100, "coal", 10, 3);
		List<GhostTrain.Spot> spots = new ArrayList<>();
		for (int i = 0; i < owners.length; i++) {
			spots.add(new GhostTrain.Spot(UUID.randomUUID(), i, 1, 1, new TrackPose(5.5, 64, 2, 0, 0)));
		}
		ghost.spots = spots;
		TrainGhosts.register(ghost);
		return ghost;
	}

	private StoredCars.Row row() {
		VehicleSnapshot snapshot = mock(VehicleSnapshot.class);
		when(snapshot.getUuid()).thenReturn("car");
		when(snapshot.getOwner()).thenReturn("character:a");
		return new StoredCars.Row(snapshot, new JSONObject());
	}

	@Test
	void aTrainOnPaperTradesAgainstItsSavedRowAndChangesTheDockOnlyOnceSaved() {
		ItemStack[] dock = storage(5, 64, 0, Chest.class, item(Material.COAL, 10));
		DockRegistry.set("world", 5, 64, 0, Mode.LOAD, "character:a");
		VehicleRepository repository = mock(VehicleRepository.class);
		framework.when(VehicleFramework::getVehicleRepository).thenReturn(repository);
		MockedStatic<StoredCars> stored = keep(mockStatic(StoredCars.class));
		StoredCars.Row row = row();
		// Each trade reads the car's containers afresh from its row.
		List<ItemStack[]> holds = new ArrayList<>();
		List<Container> fresh = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			ItemStack[] hold = new ItemStack[2];
			holds.add(hold);
			fresh.add(container(hold, true));
		}
		int[] reads = {0};
		stored.when(() -> StoredCars.read(repository, "car-0")).thenReturn(Optional.of(row));
		stored.when(() -> StoredCars.containers(row)).thenAnswer(call -> List.of(fresh.get(reads[0]++)));
		stored.when(() -> StoredCars.write(repository, row)).thenReturn(false);
		GhostTrain ghost = ghost("character:a");
		ghost.stopSerial = 4;
		CargoDocks.servePaper(ghost, 7);
		// The row could not be saved: the dock keeps its coal.
		assertEquals(10, count(dock, Material.COAL));
		stored.when(() -> StoredCars.write(repository, row)).thenReturn(true);
		CargoDocks.servePaper(ghost, 7);
		assertEquals(0, count(dock, Material.COAL));
		assertEquals(10, count(holds.get(1), Material.COAL));
		assertEquals(576 - 10, ghost.dockBudget);
		stored.verify(() -> StoredCars.putContainers(eq(row), any()), times(2));
		// Nothing left to move.
		CargoDocks.servePaper(ghost, 7);
		stored.verify(() -> StoredCars.write(repository, row), times(2));
	}

	@Test
	void aRunOnPaperThatSpawnedOrMovedOnMeanwhileLeavesTheDockAlone() {
		storage(5, 64, 0, Chest.class, item(Material.COAL, 10));
		DockRegistry.Dock dock = DockRegistry.set("world", 5, 64, 0, Mode.LOAD, "character:a");
		VehicleRepository repository = mock(VehicleRepository.class);
		MockedStatic<StoredCars> stored = keep(mockStatic(StoredCars.class));
		GhostTrain ghost = ghost("character:a");
		CargoDocks.paper(ghost, 0, dock, 0, 1);
		framework.when(VehicleFramework::getVehicleRepository).thenReturn(repository);
		CargoDocks.paper(ghost, 0, dock, 9, 1);
		ghost.dockBudget = 0;
		CargoDocks.paper(ghost, 0, dock, 0, 1);
		ghost.dockBudget = 10;
		bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(null);
		CargoDocks.paper(ghost, 0, dock, 0, 1);
		CargoDocks.servePaper(ghost, 1);
		bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
		TrainGhosts.unregister(ghost);
		CargoDocks.paper(ghost, 0, dock, 0, 1);
		TrainGhosts.register(ghost);
		stored.when(() -> StoredCars.read(repository, "car-0")).thenReturn(Optional.empty());
		CargoDocks.paper(ghost, 0, dock, 0, 1);
		stored.verify(() -> StoredCars.read(any(), any()), times(1));
		// Sold while it ran unloaded: the new owner's car does not trade with the old owner's dock.
		VehicleSnapshot sold = mock(VehicleSnapshot.class);
		when(sold.getOwner()).thenReturn("character:z");
		StoredCars.Row soldRow = new StoredCars.Row(sold, new JSONObject());
		stored.when(() -> StoredCars.read(repository, "car-0")).thenReturn(Optional.of(soldRow));
		CargoDocks.paper(ghost, 0, dock, 0, 1);
		stored.verify(() -> StoredCars.containers(any()), never());
		stored.when(() -> StoredCars.read(repository, "car-0")).thenReturn(Optional.empty());
		blocks.clear();
		CargoDocks.paper(ghost, 0, dock, 0, 1);
		stored.verify(() -> StoredCars.read(any(), any()), times(2));
		stored.verify(() -> StoredCars.write(any(), any()), never());
	}

	@Test
	void theFuelCarOnPaperTradesOnlyWhenAllowedAndRecountsItsFuel() {
		ItemStack[] dock = storage(5, 64, 0, Chest.class, item(Material.COAL, 10));
		DockRegistry.set("world", 5, 64, 0, Mode.LOAD, "character:a");
		DockRegistry.set("world", 5, 64, 1, Mode.LOAD, "character:b");
		VehicleRepository repository = mock(VehicleRepository.class);
		framework.when(VehicleFramework::getVehicleRepository).thenReturn(repository);
		MockedStatic<StoredCars> stored = keep(mockStatic(StoredCars.class));
		StoredCars.Row row = row();
		ItemStack[] hold = new ItemStack[2];
		stored.when(() -> StoredCars.read(repository, "car-1")).thenReturn(Optional.of(row));
		Container tender = container(hold, true);
		stored.when(() -> StoredCars.containers(row)).thenReturn(List.of(tender));
		stored.when(() -> StoredCars.write(repository, row)).thenReturn(true);
		GhostTrain ghost = ghost("character:b", "character:a");
		CargoDocks.servePaper(ghost, 2);
		assertEquals(10, count(dock, Material.COAL));
		Cache.trainDockFuelCars = true;
		CargoDocks.servePaper(ghost, 2);
		assertEquals(0, count(dock, Material.COAL));
		assertEquals(10, ghost.reserve);
		// A second car of a fuel type further back is left alone, as when loaded.
		Cache.trainDockFuelCars = false;
		stored.when(() -> StoredCars.read(repository, "car-2")).thenReturn(Optional.of(row));
		GhostTrain three = ghost("character:x", "character:a", "character:a");
		three.fuelCars = new boolean[] {false, false, true};
		storage(5, 64, 0, Chest.class, item(Material.COAL, 4));
		CargoDocks.servePaper(three, 2);
		stored.verify(() -> StoredCars.read(repository, "car-1"), times(2));
		stored.verify(() -> StoredCars.read(repository, "car-2"), never());
		assertEquals(14, count(hold, Material.COAL));
		Cache.trainDocksEnabled = false;
		CargoDocks.servePaper(ghost, 2);
		Cache.trainDocksEnabled = true;
		DockRegistry.load(directory.toFile());
		for (DockRegistry.Dock each : List.copyOf(DockRegistry.near("world", 5, 64, 0, 10))) {
			DockRegistry.set(each.world(), each.x(), each.y(), each.z(), null, null);
		}
		CargoDocks.servePaper(ghost, 2);
		stored.verify(() -> StoredCars.write(any(), any()), times(2));
	}

	@Test
	void docksInChunksThatAreNotLoadedLoadInTheBackgroundForOneTrade() {
		VehicleFramework plugin = mock(VehicleFramework.class);
		VehicleFramework previous = VehicleFramework.plugin;
		VehicleFramework.plugin = plugin;
		try {
			when(plugin.isEnabled()).thenReturn(true);
			DockRegistry.Dock dock = new DockRegistry.Dock("world", 16, 64, 31, Mode.LOAD, "o");
			assertEquals(3, CargoDocks.chunks(dock).size());
			assertEquals(1, CargoDocks.chunks(new DockRegistry.Dock("world", 5, 64, 5, Mode.LOAD, "o")).size());
			assertEquals(3, CargoDocks.chunks(new DockRegistry.Dock("world", 15, 64, 0, Mode.LOAD, "o")).size());
			boolean[] loaded = {false};
			when(world.isChunkLoaded(1, 1)).thenAnswer(call -> loaded[0]);
			int[] runs = {0};
			// Loads, then trades once with the chunks held.
			CompletableFuture<Chunk> loading = new CompletableFuture<>();
			when(world.getChunkAtAsync(1, 1, false)).thenReturn(loading);
			CargoDocks.whenLoaded(world, dock, () -> runs[0]++);
			assertEquals(0, runs[0]);
			loaded[0] = true;
			loading.complete(mock(Chunk.class));
			assertEquals(1, runs[0]);
			verify(world).addPluginChunkTicket(1, 1, plugin);
			verify(world).removePluginChunkTicket(1, 1, plugin);
			verify(world).addPluginChunkTicket(0, 1, plugin);
			// Already loaded: at once.
			CargoDocks.whenLoaded(world, dock, () -> runs[0]++);
			assertEquals(2, runs[0]);
			loaded[0] = false;
			// A failed load, a chunk that never loaded (never generated), or a plugin that stopped.
			CompletableFuture<Chunk> failing = new CompletableFuture<>();
			when(world.getChunkAtAsync(1, 1, false)).thenReturn(failing);
			CargoDocks.whenLoaded(world, dock, () -> runs[0]++);
			failing.completeExceptionally(new IllegalStateException("no"));
			CompletableFuture<Chunk> empty = new CompletableFuture<>();
			when(world.getChunkAtAsync(1, 1, false)).thenReturn(empty);
			CargoDocks.whenLoaded(world, dock, () -> runs[0]++);
			empty.complete(null);
			CompletableFuture<Chunk> late = new CompletableFuture<>();
			when(world.getChunkAtAsync(1, 1, false)).thenReturn(late);
			CargoDocks.whenLoaded(world, dock, () -> runs[0]++);
			when(plugin.isEnabled()).thenReturn(false);
			loaded[0] = true;
			late.complete(mock(Chunk.class));
			loaded[0] = false;
			VehicleFramework.plugin = null;
			CompletableFuture<Chunk> none = new CompletableFuture<>();
			when(world.getChunkAtAsync(1, 1, false)).thenReturn(none);
			CargoDocks.whenLoaded(world, dock, () -> runs[0]++);
			loaded[0] = true;
			none.complete(mock(Chunk.class));
			loaded[0] = false;
			assertEquals(2, runs[0]);
			// The work may fail: it is logged and the ticket is still released.
			VehicleFramework.plugin = plugin;
			when(plugin.isEnabled()).thenReturn(true);
			CompletableFuture<Chunk> again = new CompletableFuture<>();
			when(world.getChunkAtAsync(1, 1, false)).thenReturn(again);
			CargoDocks.whenLoaded(world, dock, () -> {
				throw new IllegalStateException("work failed");
			});
			loaded[0] = true;
			again.complete(mock(Chunk.class));
			verify(world, times(2)).removePluginChunkTicket(1, 1, plugin);
		} finally {
			VehicleFramework.plugin = previous;
		}
	}

	@Test
	void playersWhoOpenedABlockCycleItsDock() {
		MockedStatic<OwnershipAccess> access = keep(mockStatic(OwnershipAccess.class));
		MockedStatic<Permissions> permissions = keep(mockStatic(Permissions.class));
		Player player = mock(Player.class);
		when(player.getLocation()).thenReturn(new Location(world, 0, 64, 0));
		when(player.getName()).thenReturn("Alex");
		storage(3, 64, 3, Chest.class);
		Block block = world.getBlockAt(3, 64, 3);
		access.when(() -> OwnershipAccess.ownerValue(player)).thenReturn(Optional.empty());
		CargoDocks.cycle(player, block);
		verify(player).sendMessage("§cYou need a character to mark a cargo dock");
		access.when(() -> OwnershipAccess.ownerValue(player)).thenReturn(Optional.of("character:a"));
		CargoDocks.cycle(player, block);
		assertEquals(Mode.LOAD, DockRegistry.at("world", 3, 64, 3).mode());
		CargoDocks.cycle(player, block);
		assertEquals(Mode.UNLOAD, DockRegistry.at("world", 3, 64, 3).mode());
		CargoDocks.cycle(player, block);
		assertNull(DockRegistry.at("world", 3, 64, 3));
		verify(player).sendMessage("§eCargo dock removed");
		DockRegistry.set("world", 3, 64, 3, Mode.LOAD, "character:b");
		CargoDocks.cycle(player, block);
		verify(player).sendMessage("§cThis cargo dock belongs to someone else");
		permissions.when(() -> Permissions.isAdmin(player)).thenReturn(true);
		CargoDocks.cycle(player, block);
		assertEquals("character:b", DockRegistry.at("world", 3, 64, 3).owner());
		assertEquals(Mode.UNLOAD, DockRegistry.at("world", 3, 64, 3).mode());
	}

	@Test
	void plansWorkOnCopiesAndOnlyChangedSlotsAreWritten() {
		ItemStack coal = item(Material.COAL, 3);
		ItemStack[] before = {coal, null};
		ItemStack[] copied = CargoDocks.copy(before);
		assertNotSame(coal, copied[0]);
		assertNull(copied[1]);
		Inventory inventory = mock(Inventory.class);
		ItemStack[] after = before.clone();
		after[1] = item(Material.DIRT, 1);
		CargoDocks.applyDock(inventory, before, after);
		verify(inventory).setItem(1, after[1]);
		verify(inventory, never()).setItem(eq(0), any());
		assertTrue(CargoDocks.accepts(false).test(item(Material.SHULKER_BOX, 1)));
		assertFalse(CargoDocks.accepts(true).test(item(Material.RED_SHULKER_BOX, 1)));
		assertTrue(CargoDocks.accepts(true).test(item(Material.COAL, 1)));
		for (Class<? extends BlockState> type : List.of(org.bukkit.block.Hopper.class, org.bukkit.block.Dispenser.class,
				org.bukkit.block.Dropper.class, Barrel.class)) {
			storage(9, 9, 9, type);
			assertNotNull(CargoDocks.storage(world.getBlockAt(9, 9, 9)));
		}
	}
}
