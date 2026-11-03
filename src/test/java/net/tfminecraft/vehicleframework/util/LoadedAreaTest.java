package net.tfminecraft.vehicleframework.util;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

import org.bukkit.World;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;

class LoadedAreaTest {

    private static World allLoaded() {
        World world = mock(World.class);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        return world;
    }

    @Test
    void aSmallBoxInsideOneChunkAsksOnlyThatChunk() {
        World world = allLoaded();
        assertTrue(LoadedArea.covers(world, new BoundingBox(5, 64, 5, 6, 65, 6), 2));
        verify(world).isChunkLoaded(0, 0);
        verifyNoMoreInteractions(world);
    }

    @Test
    void theMarginReachesTheNextChunkOnlyWhenItCrossesTheBorder() {
        World world = allLoaded();
        when(world.isChunkLoaded(1, 0)).thenReturn(false);
        BoundingBox box = new BoundingBox(13.5, 64, 4, 14.5, 65, 5);
        assertTrue(LoadedArea.covers(world, box, 1));
        assertFalse(LoadedArea.covers(world, box, 2));
    }

    @Test
    void aWideBoxOverNegativeCoordinatesAsksEveryChunkUnderIt() {
        World world = allLoaded();
        BoundingBox box = new BoundingBox(-20, 0, -40, 20, 1, 10);
        assertTrue(LoadedArea.covers(world, box, 2));
        // x -22..22 -> chunks -2..1, z -42..12 -> chunks -3..0.
        verify(world, times(16)).isChunkLoaded(anyInt(), anyInt());
        verify(world).isChunkLoaded(-2, -3);
        verify(world).isChunkLoaded(1, 0);
        when(world.isChunkLoaded(-2, -3)).thenReturn(false);
        assertFalse(LoadedArea.covers(world, box, 2));
        verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
    }
}
