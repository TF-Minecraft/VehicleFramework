package net.tfminecraft.vehicleframework.vehicles.handlers.container;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** Item stacks for storage tests: stacks of one material are of one kind, whatever their amount. */
public final class StorageItems {
	private static final Map<ItemStack, Material> KINDS = new ConcurrentHashMap<>();

	private StorageItems() {
	}

	public static ItemStack item(Material type, int amount) {
		ItemStack stack = mock(ItemStack.class);
		KINDS.put(stack, type);
		when(stack.getType()).thenReturn(type);
		when(stack.getAmount()).thenReturn(amount);
		when(stack.isEmpty()).thenReturn(type == Material.AIR || amount <= 0);
		when(stack.isSimilar(any())).thenAnswer(call -> {
			ItemStack other = call.getArgument(0);
			return other != null && KINDS.get(other) == type;
		});
		when(stack.asOne()).thenAnswer(call -> item(type, 1));
		return stack;
	}
}
