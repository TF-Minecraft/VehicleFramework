package net.tfminecraft.vehicleframework.vehicles.handlers.container;

import static net.tfminecraft.vehicleframework.vehicles.handlers.container.StorageItems.item;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

class StorageAuditTest {

	@Test
	void countsEachKindWhereverItSitsAndReportsWhatMoved() {
		ItemStack air = item(Material.AIR, 1);
		ItemCounts before = ItemCounts.of(new ItemStack[] {
				item(Material.STONE, 5), null, air, item(Material.STONE, 3), item(Material.DIRT, 1) });
		assertEquals(8, before.count(item(Material.STONE, 1)));
		assertEquals(0, before.count(item(Material.GOLD_INGOT, 1)));
		assertEquals(0, ItemCounts.of(null).count(item(Material.STONE, 1)));
		ItemCounts after = ItemCounts.of(new ItemStack[] { item(Material.STONE, 2), item(Material.GOLD_INGOT, 4) });
		assertEquals(Map.of(Material.STONE, -6, Material.GOLD_INGOT, 4, Material.DIRT, -1), changes(after.since(before)));
		assertEquals(Map.of(), changes(after.since(after)));
		// The plugin taking items out counts below what is there; a kind counted to nothing is no change.
		before.add(item(Material.GOLD_INGOT, 1), -2);
		before.add(item(Material.DIRT, 1), -1);
		assertEquals(Map.of(Material.STONE, -6, Material.GOLD_INGOT, 6), changes(after.since(before)));
	}

	@Test
	void anAuditCountsOnlyWhileWatchedAndBlamesNoOneForThePluginsOwnTakes() {
		List<ItemStack> contents = new ArrayList<>(List.of(item(Material.COAL, 10)));
		Inventory inventory = mock(Inventory.class);
		when(inventory.getContents()).thenAnswer(call -> contents.toArray(new ItemStack[0]));
		StorageAudit audit = new StorageAudit();
		assertFalse(audit.isWatching());
		audit.takenByPlugin(item(Material.COAL, 1), 1);
		audit.recount(inventory);
		assertFalse(audit.isWatching());
		audit.watch(inventory);
		assertTrue(audit.isWatching());
		Player player = mock(Player.class);
		audit.acted(player);
		assertSame(player, audit.takePending());
		assertNull(audit.takePending());
		assertTrue(audit.queueCount());
		assertFalse(audit.queueCount());
		audit.countRan();
		assertTrue(audit.queueCount());
		// A locomotive draws one coal: nobody took it.
		contents.set(0, item(Material.COAL, 9));
		audit.takenByPlugin(item(Material.COAL, 1), 1);
		assertEquals(Map.of(), changes(audit.count(inventory)));
		contents.add(item(Material.IRON_INGOT, 3));
		// Watching again while watched keeps the old count.
		audit.watch(inventory);
		assertEquals(Map.of(Material.IRON_INGOT, 3), changes(audit.count(inventory)));
		// Contents the plugin loads itself are counted from afresh.
		contents.clear();
		audit.recount(inventory);
		audit.recount(null);
		assertEquals(Map.of(), changes(audit.count(inventory)));
		audit.acted(player);
		audit.stop();
		assertFalse(audit.isWatching());
		assertNull(audit.takePending());
	}

	private static Map<Material, Integer> changes(List<ItemCounts.Change> changes) {
		return changes.stream().collect(Collectors.toMap(change -> change.item().getType(), ItemCounts.Change::amount));
	}
}
