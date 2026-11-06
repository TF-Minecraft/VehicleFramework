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

    @Test
    void theMemoAsksAgainOnlyAfterAnUnloadInsideItsArea() {
        World world = allLoaded();
        LoadedArea.Memo memo = new LoadedArea.Memo();
        // x 12..18 -> chunks 0..1, z 2..6 -> chunk 0.
        BoundingBox box = new BoundingBox(14, 64, 4, 16, 65, 4);
        assertTrue(memo.covers(world, box, 2));
        verify(world, times(2)).isChunkLoaded(anyInt(), anyInt());
        // The same chunks, or fewer, are answered from memory.
        assertTrue(memo.covers(world, box, 2));
        assertTrue(memo.covers(world, new BoundingBox(5, 64, 5, 6, 65, 6), 2));
        assertTrue(memo.covers(world, new BoundingBox(20, 64, 5, 21, 65, 6), 2));
        verify(world, times(2)).isChunkLoaded(anyInt(), anyInt());
        // Unloads elsewhere, or in another world, change nothing.
        World other = mock(World.class);
        memo.forget(world, 2, 0);
        memo.forget(world, -1, 0);
        memo.forget(world, 0, 1);
        memo.forget(world, 0, -1);
        memo.forget(other, 0, 0);
        memo.forget(other);
        when(world.isChunkLoaded(1, 0)).thenReturn(false);
        assertTrue(memo.covers(world, box, 2));
        verify(world, times(2)).isChunkLoaded(anyInt(), anyInt());
        // An unload inside it asks again, and an uncovered area is asked every time.
        memo.forget(world, 1, 0);
        assertFalse(memo.covers(world, box, 2));
        assertFalse(memo.covers(world, box, 2));
        verify(world, times(6)).isChunkLoaded(anyInt(), anyInt());
        when(world.isChunkLoaded(1, 0)).thenReturn(true);
        assertTrue(memo.covers(world, box, 2));
        // A wider area, another world, or the world unloading asks again too.
        clearInvocations(world);
        assertTrue(memo.covers(world, box, 20));
        verify(world, times(12)).isChunkLoaded(anyInt(), anyInt());
        when(other.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        assertTrue(memo.covers(other, box, 2));
        verify(other, times(2)).isChunkLoaded(anyInt(), anyInt());
        memo.forget(other);
        assertTrue(memo.covers(other, box, 2));
        verify(other, times(4)).isChunkLoaded(anyInt(), anyInt());
        memo.forget(world);
        assertTrue(memo.covers(other, box, 2));
        verify(other, times(4)).isChunkLoaded(anyInt(), anyInt());
    }

    @Test
    void theMemoAsksTheWorldAgainAfterAHundredAnswers() {
        World world = allLoaded();
        LoadedArea.Memo memo = new LoadedArea.Memo();
        BoundingBox box = new BoundingBox(5, 64, 5, 6, 65, 6);
        assertTrue(memo.covers(world, box, 2));
        for (int i = 1; i < LoadedArea.Memo.MAX_REMEMBERED; i++) {
            assertTrue(memo.covers(world, box, 2));
        }
        verify(world, times(1)).isChunkLoaded(0, 0);
        assertTrue(memo.covers(world, box, 2));
        verify(world, times(2)).isChunkLoaded(0, 0);
        assertTrue(memo.covers(world, box, 2));
        verify(world, times(2)).isChunkLoaded(0, 0);
    }
}
