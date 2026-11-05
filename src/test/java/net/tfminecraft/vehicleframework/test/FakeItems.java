package net.tfminecraft.vehicleframework.test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** Item stacks that behave like real ones for counting and stacking, without a server. */
public final class FakeItems {
	private FakeItems() {
	}

	public static ItemStack item(Material material, int amount) {
		ItemStack item = mock(ItemStack.class);
		int[] count = {amount};
		when(item.getType()).thenReturn(material);
		when(item.getAmount()).thenAnswer(call -> count[0]);
		doAnswer(call -> {
			count[0] = call.getArgument(0);
			return null;
		}).when(item).setAmount(anyInt());
		when(item.getMaxStackSize()).thenReturn(material.name().endsWith("SHULKER_BOX") ? 1 : 64);
		when(item.isSimilar(any())).thenAnswer(call -> call.getArgument(0) instanceof ItemStack other
				&& other.getType() == material);
		when(item.clone()).thenAnswer(call -> item(material, count[0]));
		when(item.toString()).thenAnswer(call -> material + "x" + count[0]);
		return item;
	}

	/** Total items of a material in some slots. */
	public static int count(ItemStack[] slots, Material material) {
		int total = 0;
		for (ItemStack stack : slots) {
			if (stack != null && stack.getType() == material) {
				total += stack.getAmount();
			}
		}
		return total;
	}
}
