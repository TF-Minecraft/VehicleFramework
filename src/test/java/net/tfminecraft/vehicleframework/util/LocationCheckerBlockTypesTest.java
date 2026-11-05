package net.tfminecraft.vehicleframework.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Liquid and waterloggable are learned once per block type; the answers stay those of the block. */
class LocationCheckerBlockTypesTest {

	@BeforeEach
	@AfterEach
	void forget() {
		LocationChecker.forgetBlockTypes();
	}

	@Test
	void aTypeThatCannotBeWaterloggedSkipsItsDataAfterTheFirstBlock() {
		Block stone = block(Material.STONE, mock(BlockData.class), false);
		assertFalse(LocationChecker.isWaterBlock(stone));
		assertFalse(LocationChecker.isWaterBlock(stone));
		assertFalse(LocationChecker.isLiquidOrWater(stone));
		verify(stone, times(1)).getBlockData();
		verify(stone, times(1)).isLiquid();
	}

	@Test
	void waterloggableTypesAreReadEveryTime() {
		Waterlogged data = mock(Waterlogged.class);
		Block stairs = block(Material.OAK_STAIRS, data, false);
		when(data.isWaterlogged()).thenReturn(true);
		assertTrue(LocationChecker.isWaterBlock(stairs));
		when(data.isWaterlogged()).thenReturn(false);
		assertFalse(LocationChecker.isWaterBlock(stairs));
		assertFalse(LocationChecker.isLiquidOrWater(stairs));
		verify(stairs, times(3)).getBlockData();
	}

	@Test
	void liquidsAndWaterNeedNoBlockData() {
		Block lava = block(Material.LAVA, mock(BlockData.class), true);
		assertTrue(LocationChecker.isLiquidOrWater(lava));
		assertTrue(LocationChecker.isLiquidOrWater(lava));
		verify(lava, times(1)).isLiquid();
		Block water = block(Material.WATER, mock(BlockData.class), true);
		assertTrue(LocationChecker.isLiquidOrWater(water));
		verify(water, times(0)).isLiquid();
		verify(water, times(0)).getBlockData();
		// A block that will not say its type is asked in full every time.
		Block unknown = block(null, mock(BlockData.class), true);
		assertTrue(LocationChecker.isLiquidOrWater(unknown));
		when(unknown.isLiquid()).thenReturn(false);
		assertFalse(LocationChecker.isLiquidOrWater(unknown));
		assertFalse(LocationChecker.isWaterBlock(unknown));
		verify(unknown, times(2)).getBlockData();
	}

	private static Block block(Material type, BlockData data, boolean liquid) {
		Block block = mock(Block.class);
		when(block.getType()).thenReturn(type);
		when(block.getBlockData()).thenReturn(data);
		when(block.isLiquid()).thenReturn(liquid);
		return block;
	}
}
