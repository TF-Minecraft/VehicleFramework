package net.tfminecraft.vehicleframework.tracks;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

class CargoTransferTest {
	// A stack of a kind of item; stacks of one kind hold up to 4 here.
	record Stack(String kind, int amount) {
	}

	static final CargoTransfer.Stacks<Stack> STACKS = new CargoTransfer.Stacks<>() {
		public boolean empty(Stack stack) {
			return stack.amount() <= 0;
		}

		public int amount(Stack stack) {
			return stack.amount();
		}

		public int max(Stack stack) {
			return stack.kind().equals("tool") ? 1 : 4;
		}

		public boolean similar(Stack a, Stack b) {
			return a.kind().equals(b.kind());
		}

		public Stack copy(Stack stack, int amount) {
			return new Stack(stack.kind(), amount);
		}
	};

	@Test
	void fillsMatchingStacksFirstThenEmptySlotsUpToTheBudget() {
		Stack[] from = {new Stack("coal", 3), null, new Stack("iron", 4), new Stack("dirt", 2), new Stack("coal", 0)};
		Stack[] to = {new Stack("coal", 2), null, new Stack("iron", 2), new Stack("stone", 1), null};
		int moved = CargoTransfer.move(from, to, stack -> !stack.kind().equals("dirt"), 6, STACKS);
		assertEquals(6, moved);
		assertArrayEquals(new Stack[] {null, null, new Stack("iron", 1), new Stack("dirt", 2), new Stack("coal", 0)},
				from);
		assertArrayEquals(new Stack[] {new Stack("coal", 4), new Stack("coal", 1), new Stack("iron", 4),
				new Stack("stone", 1), new Stack("iron", 1)}, to);
		// The rest of the iron finds no room; nothing changes.
		Stack[] full = {new Stack("stone", 4)};
		Stack[] iron = {new Stack("iron", 2)};
		assertEquals(0, CargoTransfer.move(iron, full, stack -> true, 10, STACKS));
		assertEquals(new Stack("iron", 2), iron[0]);
	}

	@Test
	void unstackableItemsTakeOneSlotEach() {
		Stack[] from = {new Stack("tool", 1), new Stack("tool", 1), new Stack("tool", 1)};
		Stack[] to = {new Stack("tool", 1), null, null};
		assertEquals(2, CargoTransfer.move(from, to, stack -> true, 10, STACKS));
		assertNull(from[0]);
		assertNull(from[1]);
		assertEquals(new Stack("tool", 1), from[2]);
		assertEquals(0, CargoTransfer.move(from, to, stack -> true, 0, STACKS));
	}

	@Test
	void bukkitStacksCopyAndCompare() {
		ItemStack stack = mock(ItemStack.class);
		ItemStack copy = mock(ItemStack.class);
		ItemStack air = mock(ItemStack.class);
		when(stack.getType()).thenReturn(Material.COAL);
		when(air.getType()).thenReturn(Material.AIR);
		when(stack.getAmount()).thenReturn(5, 0);
		when(stack.getMaxStackSize()).thenReturn(0);
		when(stack.isSimilar(copy)).thenReturn(true);
		when(stack.clone()).thenReturn(copy);
		assertTrue(CargoTransfer.ITEMS.empty(null));
		assertTrue(CargoTransfer.ITEMS.empty(air));
		assertFalse(CargoTransfer.ITEMS.empty(stack));
		assertTrue(CargoTransfer.ITEMS.empty(stack));
		when(stack.getAmount()).thenReturn(5);
		assertEquals(5, CargoTransfer.ITEMS.amount(stack));
		assertEquals(1, CargoTransfer.ITEMS.max(stack));
		assertTrue(CargoTransfer.ITEMS.similar(stack, copy));
		assertSame(copy, CargoTransfer.ITEMS.copy(stack, 3));
		verify(copy).setAmount(3);
	}
}
