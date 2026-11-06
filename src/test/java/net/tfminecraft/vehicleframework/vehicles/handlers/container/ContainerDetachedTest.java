package net.tfminecraft.vehicleframework.vehicles.handlers.container;

import static net.tfminecraft.vehicleframework.test.FakeItems.item;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;

class ContainerDetachedTest {

	private static Container template() {
		YamlConfiguration config = new YamlConfiguration();
		config.set("name", "Hold");
		config.set("size", 3);
		config.set("seat", "rear");
		config.set("allow-items", List.of("coal"));
		return new Container("hold", config);
	}

	@Test
	void aDetachedCopyKeepsTheConfigurationAndHoldsItsOwnItems() {
		Container template = template();
		Container copy = Container.detached(template);
		assertEquals("hold", copy.getId());
		assertEquals("Hold", copy.getName());
		assertEquals(3, copy.getSize());
		assertEquals("rear", copy.getSeat());
		assertEquals(List.of("coal"), copy.getAllowItems());
		assertTrue(copy.getBones().isEmpty());
		assertFalse(copy.hasViewers());
		ItemStack[] empty = copy.contents();
		assertEquals(3, empty.length);
		assertNull(empty[0]);
		ItemStack coal = item(Material.COAL, 5);
		ItemStack air = item(Material.AIR, 1);
		copy.setContents(new ItemStack[] {coal, air});
		ItemStack[] held = copy.contents();
		assertEquals(5, held[0].getAmount());
		assertNotSame(coal, held[0]);
		assertNull(held[1]);
		assertNull(held[2]);
		assertTrue(template.getItems().isEmpty());
		copy.setContents(null);
		assertNull(copy.contents()[0]);
	}

	@Test
	void replacingContentsWhileWatchedBlamesNoOneForTheSwap() {
		Container hold = Container.detached(template());
		ItemStack coal = cloneable(Material.COAL, 5);
		hold.setContents(new ItemStack[] {coal});
		ItemStack[] opened = {cloneable(Material.COAL, 5)};
		ItemStack[] traded = {cloneable(Material.COAL, 3)};
		ItemStack[] shown = {cloneable(Material.COAL, 3), cloneable(Material.DIRT, 2)};
		org.bukkit.inventory.Inventory view = mock(org.bukkit.inventory.Inventory.class);
		when(view.getContents()).thenReturn(opened);
		hold.getAudit().watch(view);
		// A dock trade swaps 5 coal for 3; the view then also shows 2 dirt a player put in.
		hold.setContents(traded);
		when(view.getContents()).thenReturn(shown);
		List<ItemCounts.Change> changes = hold.getAudit().count(view);
		assertEquals(1, changes.size());
		assertEquals(Material.DIRT, changes.get(0).item().getType());
		assertEquals(2, changes.get(0).amount());
	}

	// A storage-test stack (one kind per material) that copies as itself.
	private static ItemStack cloneable(Material type, int amount) {
		ItemStack stack = StorageItems.item(type, amount);
		when(stack.clone()).thenReturn(stack);
		return stack;
	}

	@Test
	void countsItemsMatchingAnItemPath() {
		ItemAPI api = mock(ItemAPI.class, RETURNS_DEEP_STUBS);
		try (MockedStatic<TLibs> libs = mockStatic(TLibs.class)) {
			libs.when(TLibs::getItemAPI).thenReturn(api);
			ItemStack coal = item(Material.COAL, 5);
			ItemStack more = item(Material.COAL, 2);
			ItemStack dirt = item(Material.DIRT, 9);
			when(api.getChecker().checkItemWithPath(any(), eq("coal"))).thenAnswer(call ->
					call.<ItemStack>getArgument(0).getType() == Material.COAL);
			Container hold = Container.detached(template());
			hold.setContents(new ItemStack[] {coal, dirt, more});
			assertEquals(7, hold.countMatching("coal"));
			assertEquals(0, hold.countMatching(null));
			when(api.getChecker().checkItemWithPath(any(), eq("broken"))).thenThrow(new IllegalStateException("x"));
			assertEquals(0, hold.countMatching("broken"));
		}
	}
}
