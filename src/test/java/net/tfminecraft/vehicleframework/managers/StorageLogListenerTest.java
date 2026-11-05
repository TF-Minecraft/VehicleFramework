package net.tfminecraft.vehicleframework.managers;

import static net.tfminecraft.vehicleframework.vehicles.handlers.container.StorageItems.item;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.database.StorageLog;
import net.tfminecraft.vehicleframework.enums.VFGUI;
import net.tfminecraft.vehicleframework.managers.inventory.VFInventoryHolder;
import net.tfminecraft.vehicleframework.util.LogFileWriter;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.handlers.container.Container;
import net.tfminecraft.vehicleframework.vehicles.handlers.container.ContainerHandler;
import net.tfminecraft.vehicleframework.vehicles.handlers.container.StorageAudit;

class StorageLogListenerTest {
	@TempDir Path folder;
	private final boolean coreProtect = Cache.coreProtect;
	private final StorageLogListener listener = new StorageLogListener();
	private final List<ItemStack> contents = new ArrayList<>();
	private final List<HumanEntity> viewers = new ArrayList<>();
	private final List<Runnable> tasks = new ArrayList<>();
	private final Inventory top = mock(Inventory.class);
	private final ActiveVehicle vehicle = mock(ActiveVehicle.class);
	private final ContainerHandler handler = mock(ContainerHandler.class);
	private final Container container = mock(Container.class);
	private final StorageAudit audit = new StorageAudit();
	private final Player alex = player("Alex");
	private final Player bea = player("Bea");
	private MockedStatic<Bukkit> bukkit;
	// Locations hold their world weakly.
	private final World world = mock(World.class);

	@BeforeEach
	void setUp() {
		Cache.coreProtect = false;
		StorageLog.configure(true, folder.toFile());
		when(top.getContents()).thenAnswer(call -> contents.toArray(new ItemStack[0]));
		when(top.getViewers()).thenReturn(viewers);
		when(top.getHolder(false)).thenReturn(new VFInventoryHolder("cargo", VFGUI.CONTAINER, vehicle));
		when(vehicle.hasContainers()).thenReturn(true);
		when(vehicle.getContainerHandler()).thenReturn(handler);
		when(vehicle.getId()).thenReturn("wagon");
		when(vehicle.getUUID()).thenReturn("v-uuid");
		when(vehicle.getName()).thenReturn("Wagon");
		Entity entity = mock(Entity.class);
		when(world.getName()).thenReturn("world");
		when(entity.getLocation()).thenReturn(new Location(world, 1, 2, 3));
		when(vehicle.getEntity()).thenReturn(entity);
		when(handler.get("cargo")).thenReturn(container);
		when(container.getId()).thenReturn("cargo");
		when(container.getName()).thenReturn("Cargo");
		when(container.getAudit()).thenReturn(audit);
		BukkitScheduler scheduler = mock(BukkitScheduler.class);
		when(scheduler.runTask(org.mockito.ArgumentMatchers.<org.bukkit.plugin.Plugin>any(), any(Runnable.class))).thenAnswer(call -> {
			tasks.add(call.getArgument(1));
			return null;
		});
		bukkit = mockStatic(Bukkit.class);
		bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
	}

	@AfterEach
	void tearDown() {
		bukkit.close();
		Cache.coreProtect = coreProtect;
		StorageLog.configure(true, null);
	}

	@Test
	void eachPlayersClicksAreCountedOnceTheyHaveHappened() throws Exception {
		contents.add(item(Material.COAL, 10));
		viewers.add(alex);
		listener.open(new InventoryOpenEvent(view(alex)));
		viewers.add(bea);
		listener.open(new InventoryOpenEvent(view(bea)));
		// Alex shift-clicks 5 iron in; the server applies it after the event.
		fire(click(alex, false));
		contents.add(item(Material.IRON_INGOT, 5));
		// Bea's click starts: Alex's is counted, and hers waits for the end of the tick.
		fire(click(bea, false));
		contents.set(0, item(Material.COAL, 4));
		assertEquals(1, tasks.size());
		tasks.get(0).run();
		// A cancelled click moves nothing and blames nobody.
		fire(click(alex, true));
		// Alex drags gold in and closes; Bea still has it open.
		fire(drag(alex));
		contents.add(item(Material.GOLD_INGOT, 2));
		listener.closing(new InventoryCloseEvent(view(alex)));
		// Closing hands Alex back a disallowed item.
		contents.remove(contents.size() - 1);
		listener.closed(new InventoryCloseEvent(view(alex)));
		viewers.remove(alex);
		assertTrue(audit.isWatching());
		listener.closed(new InventoryCloseEvent(view(bea)));
		viewers.clear();
		assertFalse(audit.isWatching());

		assertEquals(List.of(
				"OPEN player=Alex",
				"OPEN player=Bea",
				"PUT player=Alex amount=5 item=iron_ingot",
				"TOOK player=Bea amount=6 item=coal",
				"PUT player=Alex amount=2 item=gold_ingot",
				"TOOK player=Alex amount=2 item=gold_ingot"), lines());
	}

	@Test
	void aClickIsChargedToItsPlayerWhoeverClosesOrWhatAnotherPluginDoesWithIt() throws Exception {
		viewers.add(alex);
		viewers.add(bea);
		listener.open(new InventoryOpenEvent(view(alex)));
		// Bea takes the coal another plugin cancelled her click to hand her itself.
		contents.add(item(Material.COAL, 3));
		InventoryClickEvent cancelled = click(bea, true);
		listener.beforeClick(cancelled);
		contents.clear();
		listener.click(cancelled);
		// Bea puts iron in, and Alex closes before the end of the tick.
		fire(click(bea, false));
		contents.add(item(Material.IRON_INGOT, 1));
		listener.closing(new InventoryCloseEvent(view(alex)));
		listener.closed(new InventoryCloseEvent(view(alex)));
		assertTrue(audit.isWatching());
		assertEquals(List.of(
				"OPEN player=Alex",
				"TOOK player=Bea amount=3 item=coal",
				"PUT player=Bea amount=1 item=iron_ingot"), lines());
	}

	@Test
	void clicksWhileNobodyWatchesOrByOthersAreIgnored() throws Exception {
		HumanEntity npc = mock(HumanEntity.class);
		listener.open(new InventoryOpenEvent(view(npc)));
		assertFalse(audit.isWatching());
		fire(click(alex, false));
		fire(click(npc, false));
		listener.closed(new InventoryCloseEvent(view(npc)));
		assertTrue(tasks.isEmpty());
		listener.open(new InventoryOpenEvent(view(alex)));
		contents.add(item(Material.COAL, 1));
		// A change no click explains moves the count on without a line.
		fire(click(npc, false));
		assertTrue(tasks.isEmpty());
		listener.closing(new InventoryCloseEvent(view(alex)));
		assertEquals(List.of("OPEN player=Alex"), lines());
	}

	@Test
	void onlyVehicleStorageIsWatched() {
		Inventory chest = mock(Inventory.class);
		when(chest.getHolder(false)).thenReturn(mock(InventoryHolder.class));
		assertNull(StorageLogListener.storage(view(alex, chest)));
		Inventory seats = mock(Inventory.class);
		when(seats.getHolder(false)).thenReturn(new VFInventoryHolder("v", VFGUI.SEAT_SELECTION, vehicle));
		assertNull(StorageLogListener.storage(view(alex, seats)));
		Inventory orphan = mock(Inventory.class);
		when(orphan.getHolder(false)).thenReturn(new VFInventoryHolder("cargo", VFGUI.CONTAINER));
		assertNull(StorageLogListener.storage(view(alex, orphan)));
		when(handler.get("cargo")).thenReturn(null);
		assertNull(StorageLogListener.storage(view(alex)));
		when(vehicle.hasContainers()).thenReturn(false);
		assertNull(StorageLogListener.storage(view(alex)));
		when(vehicle.hasContainers()).thenReturn(true);
		when(handler.get("cargo")).thenReturn(container);
		StorageLogListener.Storage storage = StorageLogListener.storage(view(alex));
		assertEquals(container, storage.container());
		// Nothing to do for other inventories.
		listener.open(new InventoryOpenEvent(view(alex, chest)));
		fire(click(alex, false, chest));
		fire(drag(alex, chest));
		listener.closing(new InventoryCloseEvent(view(alex, chest)));
		listener.closed(new InventoryCloseEvent(view(alex, chest)));
		assertFalse(audit.isWatching());
		assertTrue(tasks.isEmpty());
		StorageLog.configure(false, folder.toFile());
		assertNull(StorageLogListener.storage(view(alex)));
	}

	// As the server does: every listener at LOWEST, then at MONITOR.
	private void fire(InventoryClickEvent event) {
		listener.beforeClick(event);
		listener.click(event);
	}

	private void fire(InventoryDragEvent event) {
		listener.beforeDrag(event);
		listener.drag(event);
	}

	private List<String> lines() throws Exception {
		LogFileWriter.flush();
		return Files.readAllLines(folder.resolve("logs/storage.log")).stream()
				.map(line -> line.substring("yyyy-MM-dd HH:mm:ss ".length()))
				.map(line -> line.replaceAll(" \\([0-9a-f-]{36}\\)", "").replace(
						" storage=cargo \"Cargo\" vehicle=wagon \"Wagon\" uuid=v-uuid at world 1,2,3", ""))
				.toList();
	}

	private InventoryView view(HumanEntity who) {
		return view(who, top);
	}

	private static InventoryView view(HumanEntity who, Inventory inventory) {
		InventoryView view = mock(InventoryView.class);
		when(view.getTopInventory()).thenReturn(inventory);
		when(view.getPlayer()).thenReturn(who);
		return view;
	}

	private InventoryClickEvent click(HumanEntity who, boolean cancelled) {
		return click(who, cancelled, top);
	}

	private static InventoryClickEvent click(HumanEntity who, boolean cancelled, Inventory inventory) {
		InventoryClickEvent event = mock(InventoryClickEvent.class);
		InventoryView view = view(who, inventory);
		when(event.getView()).thenReturn(view);
		when(event.getWhoClicked()).thenReturn(who);
		when(event.isCancelled()).thenReturn(cancelled);
		return event;
	}

	private InventoryDragEvent drag(HumanEntity who) {
		return drag(who, top);
	}

	private static InventoryDragEvent drag(HumanEntity who, Inventory inventory) {
		InventoryDragEvent event = mock(InventoryDragEvent.class);
		InventoryView view = view(who, inventory);
		when(event.getView()).thenReturn(view);
		when(event.getWhoClicked()).thenReturn(who);
		return event;
	}

	private static Player player(String name) {
		Player player = mock(Player.class);
		when(player.getName()).thenReturn(name);
		when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes()));
		return player;
	}
}
