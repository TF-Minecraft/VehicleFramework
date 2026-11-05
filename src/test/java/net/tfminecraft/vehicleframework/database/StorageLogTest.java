package net.tfminecraft.vehicleframework.database;

import static net.tfminecraft.vehicleframework.vehicles.handlers.container.StorageItems.item;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import net.kyori.adventure.text.Component;
import net.tfminecraft.coreprotect.CoreProtectAPI;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;
import net.tfminecraft.tlibs.objects.api.subapi.ItemChecker;
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
	void itemsAreNamedByPluginPathNameAndWhatTheyHold() throws Exception {
		StorageLog.configure(true, folder.toFile());
		Cache.coreProtect = false;
		ItemStack steel = item(Material.IRON_INGOT, 1);
		ItemStack plain = item(Material.STONE, 1);
		ItemStack quoted = item(Material.NAME_TAG, 1);
		ItemMeta quotedMeta = mock(ItemMeta.class);
		when(quoted.hasItemMeta()).thenReturn(true);
		when(quoted.getItemMeta()).thenReturn(quotedMeta);
		when(quotedMeta.hasItemName()).thenReturn(true);
		// A copy of one keeps its name, as a real item's does.
		when(quoted.asOne()).thenReturn(quoted);
		when(quotedMeta.itemName()).thenReturn(Component.text("The \"Boss\" \\ tag"));
		ItemStack bundle = item(Material.BUNDLE, 1);
		BundleMeta bundleMeta = mock(BundleMeta.class);
		when(bundle.hasItemMeta()).thenReturn(true);
		when(bundle.getItemMeta()).thenReturn(bundleMeta);
		List<ItemStack> packed = List.of(item(Material.DIAMOND, 3), quoted, item(Material.DIAMOND, 2));
		when(bundleMeta.getItems()).thenReturn(packed);
		ItemStack box = item(Material.SHULKER_BOX, 1);
		BlockStateMeta boxMeta = mock(BlockStateMeta.class);
		ShulkerBox state = mock(ShulkerBox.class);
		Inventory inside = mock(Inventory.class);
		when(box.hasItemMeta()).thenReturn(true);
		when(box.getItemMeta()).thenReturn(boxMeta);
		when(boxMeta.hasBlockState()).thenReturn(true);
		when(boxMeta.getBlockState()).thenReturn(state);
		when(state.getSnapshotInventory()).thenReturn(inside);
		ItemStack[] boxed = { null, item(Material.EMERALD, 7) };
		when(inside.getContents()).thenReturn(boxed);
		ItemStack sealed = item(Material.CHEST, 1);
		BlockStateMeta sealedMeta = mock(BlockStateMeta.class);
		when(sealed.hasItemMeta()).thenReturn(true);
		when(sealed.getItemMeta()).thenReturn(sealedMeta);
		ItemAPI api = mock(ItemAPI.class);
		ItemChecker checker = mock(ItemChecker.class);
		when(api.getChecker()).thenReturn(checker);
		when(checker.getAsStringPath(steel)).thenReturn("m.MATERIAL.STEEL_INGOT");
		when(checker.getAsStringPath(plain)).thenReturn("v.stone");
		when(checker.getAsStringPath(quoted)).thenReturn(" ");
		Player player = player();
		ActiveVehicle vehicle = vehicle(null);
		Container container = container();
		try (MockedStatic<TLibs> tlibs = mockStatic(TLibs.class)) {
			tlibs.when(TLibs::getItemAPI).thenReturn(api);
			for (ItemStack item : List.of(steel, plain, quoted, bundle, box, sealed)) {
				StorageLog.changed(player, vehicle, container, item, 1);
			}
		}
		String tail = " storage=cargo \"Cargo Hold\" vehicle=wagon_cargo \"Cargo Wagon\" uuid=v-uuid at ? ?";
		String who = "PUT player=Alex (" + PLAYER + ") amount=1 ";
		assertEquals(List.of(
				who + "item=iron_ingot path=m.MATERIAL.STEEL_INGOT" + tail,
				who + "item=stone" + tail,
				who + "item=name_tag name=\"The \\\"Boss\\\" \\\\ tag\"" + tail,
				who + "item=bundle contents=[5 diamond, 1 name_tag \"The \\\"Boss\\\" \\\\ tag\"]" + tail,
				who + "item=shulker_box contents=[7 emerald]" + tail,
				who + "item=chest" + tail), lines());
	}

	@Test
	void anItemThatCannotBeDescribedIsLoggedByItsTypeAndNamesStayOnOneLine() throws Exception {
		StorageLog.configure(true, folder.toFile());
		Cache.coreProtect = false;
		ItemStack odd = item(Material.CHEST, 1);
		when(odd.hasItemMeta()).thenThrow(new IllegalStateException("bad component"));
		StorageLog.changed(player(), vehicle(null), container(), odd, -1);
		assertEquals(1, lines().size());
		assertTrue(lines().get(0).startsWith("TOOK player=Alex (" + PLAYER + ") amount=1 item=chest storage="));
		String nl = String.valueOf((char) 10);
		assertEquals("\"a\\u000ab\\u007f\"", StorageLog.quoted("a" + nl + "b" + (char) 127));
		// A path that is not a plain id is quoted, so a renamed item cannot fake another line.
		assertEquals("m.MATERIAL.STEEL_INGOT", StorageLog.pathField("m.MATERIAL.STEEL_INGOT"));
		assertEquals("ia.tfmc:train_track/x-1", StorageLog.pathField("ia.tfmc:train_track/x-1"));
		assertEquals("\"Nl one\\u000a2026 TOOK player=Fake\"", StorageLog.pathField("Nl one" + nl + "2026 TOOK player=Fake"));
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
			verifyNoMoreInteractions(api);
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
