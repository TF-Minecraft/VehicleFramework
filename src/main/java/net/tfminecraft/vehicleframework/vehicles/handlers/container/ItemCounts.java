package net.tfminecraft.vehicleframework.vehicles.handlers.container;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.inventory.ItemStack;

/**
 * How many of each kind of item a storage holds. Items of one kind stack together
 * ({@link ItemStack#isSimilar}), wherever they sit. A count may go below zero when an item taken
 * by the plugin itself is subtracted from an earlier count.
 */
public final class ItemCounts {
	// One item of each kind, kept apart from the inventory, and how many of it there are.
	private final List<ItemStack> kinds = new ArrayList<>();
	private final List<Integer> amounts = new ArrayList<>();

	/** One storage change: a positive amount was put in, a negative one taken out. */
	public record Change(ItemStack item, int amount) { }

	public static ItemCounts of(ItemStack[] contents) {
		ItemCounts counts = new ItemCounts();
		if (contents != null) {
			for (ItemStack stack : contents) {
				if (stack != null && !stack.isEmpty()) {
					counts.add(stack, stack.getAmount());
				}
			}
		}
		return counts;
	}

	public void add(ItemStack item, int amount) {
		int index = indexOf(item);
		if (index < 0) {
			// Inventory contents can mirror the live slot, so keep a copy of the kind.
			kinds.add(item.asOne());
			amounts.add(amount);
		} else {
			amounts.set(index, amounts.get(index) + amount);
		}
	}

	public int count(ItemStack item) {
		int index = indexOf(item);
		return index < 0 ? 0 : amounts.get(index);
	}

	/** What changed from {@code before} to these counts, kind by kind. */
	public List<Change> since(ItemCounts before) {
		List<Change> changes = new ArrayList<>();
		for (int i = 0; i < kinds.size(); i++) {
			int change = amounts.get(i) - before.count(kinds.get(i));
			if (change != 0) {
				changes.add(new Change(kinds.get(i), change));
			}
		}
		for (int i = 0; i < before.kinds.size(); i++) {
			ItemStack kind = before.kinds.get(i);
			if (indexOf(kind) < 0 && before.amounts.get(i) != 0) {
				changes.add(new Change(kind, -before.amounts.get(i)));
			}
		}
		return changes;
	}

	private int indexOf(ItemStack item) {
		for (int i = 0; i < kinds.size(); i++) {
			if (kinds.get(i).isSimilar(item)) {
				return i;
			}
		}
		return -1;
	}
}
