package net.tfminecraft.vehicleframework.tracks;

import java.util.function.Predicate;

import org.bukkit.inventory.ItemStack;

/**
 * Moves items from one set of slots to another, as much as fits: first onto matching
 * stacks, then into empty slots. Works on copies of the contents, so a caller can save
 * one side before changing the other and never lose or double an item.
 */
public final class CargoTransfer {

	/** What the transfer needs to know about a stack. */
	public interface Stacks<T> {
		boolean empty(T stack);

		int amount(T stack);

		int max(T stack);

		boolean similar(T a, T b);

		/** A copy of {@code stack} with {@code amount} items. */
		T copy(T stack, int amount);
	}

	/** Bukkit item stacks. */
	public static final Stacks<ItemStack> ITEMS = new Stacks<>() {
		@Override
		public boolean empty(ItemStack stack) {
			return stack == null || stack.getType().isAir() || stack.getAmount() <= 0;
		}

		@Override
		public int amount(ItemStack stack) {
			return stack.getAmount();
		}

		@Override
		public int max(ItemStack stack) {
			return Math.max(1, stack.getMaxStackSize());
		}

		@Override
		public boolean similar(ItemStack a, ItemStack b) {
			return a.isSimilar(b);
		}

		@Override
		public ItemStack copy(ItemStack stack, int amount) {
			ItemStack copy = stack.clone();
			copy.setAmount(amount);
			return copy;
		}
	};

	private CargoTransfer() {
	}

	/**
	 * Moves up to {@code budget} items from {@code from} into {@code to}, skipping stacks
	 * {@code accepts} refuses. Both arrays are changed in place. Returns the items moved.
	 */
	public static <T> int move(T[] from, T[] to, Predicate<T> accepts, int budget, Stacks<T> stacks) {
		int moved = 0;
		for (int i = 0; i < from.length && moved < budget; i++) {
			T stack = from[i];
			if (stack == null || stacks.empty(stack) || !accepts.test(stack)) {
				continue;
			}
			int want = Math.min(stacks.amount(stack), budget - moved);
			int placed = place(to, stack, want, stacks);
			if (placed <= 0) {
				continue;
			}
			moved += placed;
			int left = stacks.amount(stack) - placed;
			from[i] = left <= 0 ? null : stacks.copy(stack, left);
		}
		return moved;
	}

	// Puts up to amount of stack into the slots; returns how many fitted.
	private static <T> int place(T[] to, T stack, int amount, Stacks<T> stacks) {
		int left = amount;
		int max = stacks.max(stack);
		for (int i = 0; i < to.length && left > 0; i++) {
			T there = to[i];
			if (there == null || stacks.empty(there) || !stacks.similar(there, stack)) {
				continue;
			}
			int room = max - stacks.amount(there);
			if (room <= 0) {
				continue;
			}
			int add = Math.min(room, left);
			to[i] = stacks.copy(there, stacks.amount(there) + add);
			left -= add;
		}
		for (int i = 0; i < to.length && left > 0; i++) {
			if (to[i] != null && !stacks.empty(to[i])) {
				continue;
			}
			int add = Math.min(max, left);
			to[i] = stacks.copy(stack, add);
			left -= add;
		}
		return amount - left;
	}
}
