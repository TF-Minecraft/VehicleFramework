package net.tfminecraft.vehicleframework.database;

import static net.tfminecraft.vehicleframework.vehicles.handlers.container.StorageItems.item;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import net.kyori.adventure.text.Component;
import net.tfminecraft.coreprotect.CoreProtectAPI;
import net.tfminecraft.vehicleframework.VehicleFramework;
import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.util.LogFileWriter;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.handlers.container.Container;

class StorageLogTest {
	private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
	@TempDir Path folder;
	private final boolean coreProtect = Cache.coreProtect;
	// Locations hold their world weakly.
	private final World harbour = world("harbour");

	@AfterEach
	void restore() {
		Cache.coreProtect = coreProtect;
		StorageLog.configure(true, null);
	}

	@Test
	void eachChangeIsWrittenWithWhoWhatAndWhere() throws Exception {
		StorageLog.configure(true, folder.toFile());
		assertTrue(StorageLog.isEnabled());
		Cache.coreProtect = false;
		Player player = player();
		ActiveVehicle vehicle = vehicle(new Location(harbour, 120.7, 64, -30.2));
		Container container = container();
		ItemStack ingot = item(Material.IRON_INGOT, 1);
		ItemStack named = item(Material.PAPER, 1);
		ItemMeta meta = mock(ItemMeta.class);
		when(named.hasItemMeta()).thenReturn(true);
		when(named.getItemMeta()).thenReturn(meta);
		when(meta.hasDisplayName()).thenReturn(true);
		when(meta.displayName()).thenReturn(Component.text("Sealed Letter"));
		ItemStack plainMeta = item(Material.BREAD, 1);
		when(plainMeta.hasItemMeta()).thenReturn(true);
		when(plainMeta.getItemMeta()).thenReturn(mock(ItemMeta.class));

		StorageLog.opened(player, vehicle, container);
		StorageLog.changed(player, vehicle, container, ingot, 12);
		StorageLog.changed(player, vehicle, container, named, -1);
		StorageLog.changed(player, vehicle, container, plainMeta, -3);
		StorageLog.changed(player, vehicle, container, ingot, 0);

		String where = "storage=cargo \"Cargo Hold\" vehicle=wagon_cargo \"Cargo Wagon\" uuid=v-uuid at harbour 120,64,-31";
		String who = "player=Alex (" + PLAYER + ")";
		assertEquals(List.of(
				"OPEN " + who + " " + where,
				"PUT " + who + " amount=12 item=iron_ingot " + where,
				"TOOK " + who + " amount=1 item=paper name=\"Sealed Letter\" " + where,
				"TOOK " + who + " amount=3 item=bread " + where), lines());
	}

	@Test
	void coreProtectGetsAChatLineAndAnInteractionAtTheVehicle() {
		StorageLog.configure(true, null);
		Player player = player();
		Location at = new Location(harbour, 1, 2, 3);
		ActiveVehicle vehicle = vehicle(at);
		Container container = container();
		CoreProtectAPI api = mock(CoreProtectAPI.class);
		try (MockedStatic<VehicleFramework> framework = mockStatic(VehicleFramework.class)) {
			framework.when(VehicleFramework::getCoreProtect).thenReturn(api);
			Cache.coreProtect = true;
			StorageLog.opened(player, vehicle, container);
			StorageLog.changed(player, vehicle, container, item(Material.IRON_INGOT, 1), 12);
			StorageLog.changed(player, vehicle, container, item(Material.COAL, 1), -2);
			verify(api).logInteraction("Alex", at);
			verify(api).logChat(player, "[VF storage] put 12 iron_ingot into Cargo Wagon (wagon_cargo v-uuid) storage cargo at 1,2,3");
			verify(api).logChat(player, "[VF storage] took 2 coal from Cargo Wagon (wagon_cargo v-uuid) storage cargo at 1,2,3");
			// A vehicle with no entity left logs no interaction.
			ActiveVehicle gone = vehicle(null);
			StorageLog.opened(player, gone, container);
			StorageLog.changed(player, gone, container, item(Material.COAL, 1), 1);
			verify(api).logChat(player, "[VF storage] put 1 coal into Cargo Wagon (wagon_cargo v-uuid) storage cargo at ?");
			// CoreProtect hooked but its API off.
			framework.when(VehicleFramework::getCoreProtect).thenReturn(null);
			StorageLog.opened(player, vehicle, container);
			StorageLog.changed(player, vehicle, container, item(Material.COAL, 1), 1);
		}
	}

	@Test
	void turnedOffItWritesNothing() throws Exception {
		StorageLog.configure(false, folder.toFile());
		assertFalse(StorageLog.isEnabled());
		CoreProtectAPI api = mock(CoreProtectAPI.class);
		try (MockedStatic<VehicleFramework> framework = mockStatic(VehicleFramework.class)) {
			framework.when(VehicleFramework::getCoreProtect).thenReturn(api);
			Cache.coreProtect = true;
			StorageLog.opened(player(), vehicle(null), container());
			StorageLog.changed(player(), vehicle(null), container(), item(Material.COAL, 1), 1);
		}
		verifyNoInteractions(api);
		LogFileWriter.flush();
		assertFalse(Files.exists(folder.resolve("logs/storage.log")));
	}

	private List<String> lines() throws Exception {
		LogFileWriter.flush();
		return Files.readAllLines(folder.resolve("logs/storage.log")).stream()
				.map(line -> line.substring("yyyy-MM-dd HH:mm:ss ".length()))
				.toList();
	}

	private static Player player() {
		Player player = mock(Player.class);
		when(player.getName()).thenReturn("Alex");
		when(player.getUniqueId()).thenReturn(PLAYER);
		return player;
	}

	private static World world(String name) {
		World world = mock(World.class);
		when(world.getName()).thenReturn(name);
		return world;
	}

	private static ActiveVehicle vehicle(Location at) {
		ActiveVehicle vehicle = mock(ActiveVehicle.class);
		when(vehicle.getId()).thenReturn("wagon_cargo");
		when(vehicle.getUUID()).thenReturn("v-uuid");
		when(vehicle.getName()).thenReturn("§aCargo §x§f§f§0§0§0§0Wagon");
		if (at != null) {
			Entity entity = mock(Entity.class);
			when(entity.getLocation()).thenAnswer(call -> at.clone());
			when(vehicle.getEntity()).thenReturn(entity);
		}
		return vehicle;
	}

	private static Container container() {
		Container container = mock(Container.class);
		when(container.getId()).thenReturn("cargo");
		when(container.getName()).thenReturn("§7Cargo Hold");
		return container;
	}
}
