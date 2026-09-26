package net.tfminecraft.vehicleframework.tracks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.junit.jupiter.api.Test;

class TrainSpaceHighlightTest {
    @Test
    void minedBlockLosesItsOutline() {
        BlockDisplay display = display(true, mock(BlockData.class), shown(Material.STONE));
        List<BlockDisplay> displays = new ArrayList<>(List.of(display));
        TrainSpaceHighlight.refresh(displays, Set.of());
        assertEquals(0, displays.size());
        verify(display).remove();
    }

    @Test
    void unchangedBlockKeepsItsOutline() {
        BlockData data = shown(Material.STONE);
        BlockDisplay display = display(false, data, data);
        List<BlockDisplay> displays = new ArrayList<>(List.of(display));
        TrainSpaceHighlight.refresh(displays, Set.of());
        assertEquals(1, displays.size());
        verify(display, never()).remove();
        verify(display, never()).setBlock(data);
    }

    @Test
    void replacedBlockRedrawsItsOutline() {
        BlockData now = mock(BlockData.class);
        BlockDisplay display = display(false, now, shown(Material.STONE));
        List<BlockDisplay> displays = new ArrayList<>(List.of(display));
        TrainSpaceHighlight.refresh(displays, Set.of());
        assertEquals(1, displays.size());
        verify(display).setBlock(now);
    }

    @Test
    void openSpaceMarkerStays() {
        BlockDisplay display = display(true, mock(BlockData.class), shown(Material.RED_STAINED_GLASS));
        List<BlockDisplay> displays = new ArrayList<>(List.of(display));
        TrainSpaceHighlight.refresh(displays, Set.of(display));
        assertEquals(1, displays.size());
        verify(display, never()).remove();
    }

    @Test
    void realRedGlassInTheWayIsStillRefreshed() {
        BlockDisplay display = display(true, mock(BlockData.class), shown(Material.RED_STAINED_GLASS));
        List<BlockDisplay> displays = new ArrayList<>(List.of(display));
        TrainSpaceHighlight.refresh(displays, Set.of());
        assertEquals(0, displays.size());
        verify(display).remove();
    }

    @Test
    void removedDisplayIsForgotten() {
        BlockDisplay display = mock(BlockDisplay.class);
        when(display.isValid()).thenReturn(false);
        List<BlockDisplay> displays = new ArrayList<>(List.of(display));
        TrainSpaceHighlight.refresh(displays, Set.of());
        assertEquals(0, displays.size());
    }

    private static BlockData shown(Material material) {
        BlockData data = mock(BlockData.class);
        when(data.getMaterial()).thenReturn(material);
        return data;
    }

    private static BlockDisplay display(boolean passable, BlockData blockNow, BlockData shown) {
        Block block = mock(Block.class);
        when(block.isPassable()).thenReturn(passable);
        when(block.getBlockData()).thenReturn(blockNow);
        Location location = mock(Location.class);
        when(location.getBlock()).thenReturn(block);
        BlockDisplay display = mock(BlockDisplay.class);
        when(display.isValid()).thenReturn(true);
        when(display.getLocation()).thenReturn(location);
        when(display.getBlock()).thenReturn(shown);
        return display;
    }
}
