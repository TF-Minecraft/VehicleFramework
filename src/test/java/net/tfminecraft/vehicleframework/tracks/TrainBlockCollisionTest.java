package net.tfminecraft.vehicleframework.tracks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.tfminecraft.vehicleframework.cache.Cache;

class TrainBlockCollisionTest {
    private static final double REACH = 5;
    private double width;
    private double height;
    private double yOffset;

    private interface Solid {
        boolean at(int x, int y, int z);
    }

    @BeforeEach
    void setUp() {
        width = Cache.trainClearanceWidth;
        height = Cache.trainClearanceHeight;
        yOffset = Cache.trackVehicleYOffset;
        Cache.trainClearanceWidth = 3;
        Cache.trainClearanceHeight = 3;
        Cache.trackVehicleYOffset = 0.51;
    }

    @AfterEach
    void restore() {
        Cache.trainClearanceWidth = width;
        Cache.trainClearanceHeight = height;
        Cache.trackVehicleYOffset = yOffset;
    }

    @Test
    void threeByThreeTunnelIsClear() {
        assertFalse(blocked(northTrack(), tunnel(3)));
    }

    @Test
    void threeByThreeTunnelAlongXIsClear() {
        TrackSpline track = TrackSpline.fromPoints(UUID.randomUUID(), "world", false,
                List.of(new double[]{0, 64, 0.5}, new double[]{100, 64, 0.5}));
        assertFalse(blocked(track, (x, y, z) -> tunnel(3).at(z, y, x)));
    }

    @Test
    void roofTwoBlocksUpBlocks() {
        assertTrue(blocked(northTrack(), tunnel(2)));
    }

    @Test
    void lowerHeightSettingFitsTwoHighTunnel() {
        Cache.trainClearanceHeight = 2;
        assertFalse(blocked(northTrack(), tunnel(2)));
    }

    @Test
    void widerWidthSettingHitsTunnelWalls() {
        Cache.trainClearanceWidth = 3.5;
        assertTrue(blocked(northTrack(), tunnel(3)));
    }

    @Test
    void blockPastFrontCouplerBlocks() {
        // Front coupler moves from z 25.9 to 26.15.
        assertTrue(blocked(northTrack(), (x, y, z) -> tunnel(3).at(x, y, z) || (x == 0 && y == 64 && z == 26)));
    }

    @Test
    void blockJustBeyondStepIsClear() {
        assertFalse(blocked(northTrack(), (x, y, z) -> tunnel(3).at(x, y, z) || (x == 0 && y == 64 && z == 27)));
    }

    @Test
    void diagonalTunnelThreeWideIsClear() {
        // Blocks at |x - z| >= 4 stay at least 1.5 from the rail centre line; |x - z| == 3 would not.
        assertFalse(blocked(diagonalTrack(), diagonalTunnel(4)));
    }

    @Test
    void diagonalTunnelNarrowerThanClearanceBlocks() {
        assertTrue(blocked(diagonalTrack(), diagonalTunnel(3)));
    }

    @Test
    void blockAlreadyOverlappingFrontStillBlocksMovingIntoIt() {
        // Front coupler starts at z 25.9, inside the block from z 25 to 26.
        Solid solid = (x, y, z) -> tunnel(3).at(x, y, z) || (x == 0 && y == 64 && z == 25);
        assertTrue(blocked(northTrack(), solid, 20.9, 21.15));
        assertFalse(blocked(northTrack(), solid, 20.9, 20.65));
    }

    @Test
    void blockPastTrackEndIsChecked() {
        TrackSpline track = TrackSpline.fromPoints(UUID.randomUUID(), "world", false,
                List.of(new double[]{0.5, 64, 0}, new double[]{0.5, 64, 30}));
        // Moving to s 29.25 takes the front coupler to z 34.25, past the track end at z 30.
        Solid solid = (x, y, z) -> tunnel(3).at(x, y, z) || (x == 0 && y == 64 && z == 34);
        assertTrue(blocked(track, solid, 29, 29.25));
        assertFalse(blocked(track, tunnel(3), 29, 29.25));
    }

    @Test
    void blockOverlappingFrontStillBlocksAcrossTrackChange() {
        TrackSpline stem = TrackSpline.fromPoints(UUID.randomUUID(), "world", false,
                List.of(new double[]{0.5, 64, 0}, new double[]{0.5, 64, 20}));
        TrackSpline next = TrackSpline.fromPoints(UUID.randomUUID(), "world", false,
                List.of(new double[]{0.5, 64, 20}, new double[]{0.5, 64, 60}));
        // Front coupler moves from z 24.9 (past the stem's end) to 25.15 on the next track.
        Solid solid = (x, y, z) -> tunnel(3).at(x, y, z) || (x == 0 && y == 64 && z == 24);
        Entity entity = mock(Entity.class);
        World world = world(solid);
        when(entity.getWorld()).thenReturn(world);
        assertTrue(TrainBlockCollision.blocked(entity, stem, 19.9, next, 0.15, REACH));
        assertFalse(TrainBlockCollision.blocked(entity, next, 0.15, stem, 19.9, REACH));
    }

    @Test
    void blockSharedByBothTracksStillBlocksOnBentBranch() {
        TrackSpline stem = TrackSpline.fromPoints(UUID.randomUUID(), "world", false,
                List.of(new double[]{0.5, 64, 0}, new double[]{0.5, 64, 20}));
        List<double[]> bend = new java.util.ArrayList<>();
        for (int i = 0; i <= 20; i++) {
            double a = Math.toRadians(i * 3);
            bend.add(new double[]{0.5 + 20 * (1 - Math.cos(a)), 64, 20 + 20 * Math.sin(a)});
        }
        TrackSpline branch = TrackSpline.fromPoints(UUID.randomUUID(), "world", false, bend);
        // At x 1 to 2 the block sits on the edge of the stem's space, so the car already
        // overlaps it. The branch bends towards +x and runs further into it.
        Solid solid = (x, y, z) -> y == 64 && x == 1 && z == 23;
        Entity entity = mock(Entity.class);
        World world = world(solid);
        when(entity.getWorld()).thenReturn(world);
        assertFalse(TrainBlockCollision.blocked(entity, stem, 19.9, stem, 19.95, REACH));
        assertTrue(TrainBlockCollision.blocked(entity, stem, 19.9, branch, 0.15, REACH));
    }

    @Test
    void obstructionsAreListedOnceInTrackOrder() {
        World world = world((x, y, z) -> tunnel(3).at(x, y, z)
                || (x == 1 && y == 65 && z == 40) || (x == -1 && y == 64 && z == 12));
        List<TrainBlockCollision.Obstruction> found =
                TrainBlockCollision.obstructions(world, northTrack(), 0, 100);
        assertEquals(2, found.size());
        assertEquals(12, found.get(0).z());
        assertEquals(40, found.get(1).z());
        assertTrue(found.get(0).s() < found.get(1).s());
    }

    @Test
    void scanFindsEveryBlockAMovingCarCanHitOnAGrade() {
        Cache.trainClearanceHeight = 2.5;
        TrackSpline ramp = TrackSpline.fromPoints(UUID.randomUUID(), "world", false,
                List.of(new double[]{0.5, 64, 0}, new double[]{0.5, 80, 100}));
        // A stepped roof, one block up every six, just low enough to clip in places.
        Solid solid = (x, y, z) -> y >= 66 + Math.floorDiv(z, 6) || y <= 63 + Math.floorDiv(z, 7);
        World world = world(solid);
        Entity entity = mock(Entity.class);
        when(entity.getWorld()).thenReturn(world);
        java.util.Set<String> scanned = new java.util.HashSet<>();
        for (TrainBlockCollision.Obstruction o : TrainBlockCollision.obstructions(world, ramp, 0, ramp.length())) {
            scanned.add(o.x() + "," + o.y() + "," + o.z());
        }
        int checked = 0;
        for (double s = REACH; s < ramp.length() - REACH - 0.3; s += 0.07) {
            for (TrainBlockCollision.Obstruction o : TrainBlockCollision.blockers(
                    entity, ramp, s, ramp, s + 0.25, REACH, new java.util.HashMap<>())) {
                checked++;
                assertTrue(scanned.contains(o.x() + "," + o.y() + "," + o.z()),
                        "Scan missed " + o.x() + "," + o.y() + "," + o.z() + " that stops a car at s " + s);
            }
        }
        assertTrue(checked > 0, "The roof should stop the car somewhere");
    }

    @Test
    void scanSkipsUnloadedChunks() {
        World world = world(tunnel(2));
        TrainBlockCollision.Scan scan = TrainBlockCollision.scanLoaded(world, northTrack());
        assertTrue(scan.obstructions().isEmpty());
        assertEquals(100, scan.skipped(), 1e-6);
    }

    @Test
    void scanChecksLoadedBlocksNextToTrackInUnloadedChunks() {
        // Track along x = 15.5, in chunk 0; the wall at x 16 to 17 is in loaded chunk 1.
        TrackSpline edge = TrackSpline.fromPoints(UUID.randomUUID(), "world", false,
                List.of(new double[]{14.9, 64, 0}, new double[]{14.9, 64, 40}));
        World world = world((x, y, z) -> y == 64 && x == 16 && z == 20);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenAnswer(call -> (int) call.getArgument(0) == 1);
        TrainBlockCollision.Scan scan = TrainBlockCollision.scanLoaded(world, edge);
        assertEquals(1, scan.obstructions().size());
        assertEquals(40, scan.skipped(), 1e-6);
    }

    @Test
    void scanChecksLoadedChunks() {
        World world = world(tunnel(2));
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        TrainBlockCollision.Scan scan = TrainBlockCollision.scanLoaded(world, northTrack());
        assertFalse(scan.obstructions().isEmpty());
        assertEquals(0, scan.skipped(), 1e-6);
    }

    @Test
    void layingRefusesTrackTrainsCannotUse() {
        World world = world(tunnel(2));
        List<double[]> points = List.of(new double[]{0.5, 64, 10}, new double[]{0.5, 64, 30});
        TrackLayException refused = assertThrows(TrackLayException.class,
                () -> TrackClearance.checkTrainSpace(world, points));
        assertFalse(refused.inTrainSpace.isEmpty());
        assertTrue(refused.hasBlock());
        assertTrue(refused.getMessage().contains("in the way"), refused.getMessage());
    }

    @Test
    void singleBlockRefusalAlsoListsTheTrainSpace() {
        World world = world(tunnel(2));
        List<double[]> points = List.of(new double[]{0.5, 64, 10}, new double[]{0.5, 64, 30});
        TrackLayException step = new TrackLayException("Cannot lay track: stone in the way at 0, 64, 12.", 0, 64, 12);
        TrackLayException refused = TrackClearance.withTrainSpace(world, points, step);
        assertTrue(refused.inTrainSpace.size() > 1);
        assertEquals(12, refused.inTrainSpace.get(0).z());
        assertEquals(12, (int) refused.blockZ);
        assertTrue(refused.getMessage().startsWith("Cannot lay track: stone in the way at 0, 64, 12, and "),
                refused.getMessage());
    }

    @Test
    void singleBlockRefusalWithNothingElseStaysAsItWas() {
        World world = world(tunnel(3));
        List<double[]> points = List.of(new double[]{0.5, 64, 10}, new double[]{0.5, 64, 30});
        TrackLayException step = new TrackLayException("Cannot lay track: stone in the way at 0, 64, 12.", 0, 64, 12);
        assertEquals(step, TrackClearance.withTrainSpace(world, points, step));
    }

    @Test
    void layingAllowsTrackTrainsCanUse() throws TrackLayException {
        World world = world(tunnel(3));
        TrackClearance.checkTrainSpace(world, List.of(new double[]{0.5, 64, 10}, new double[]{0.5, 64, 30}));
    }

    @Test
    void noWorldIsNeverBlocked() {
        Entity entity = mock(Entity.class);
        TrackSpline track = northTrack();
        assertFalse(TrainBlockCollision.blocked(entity, track, 20, track, 20.25, REACH));
    }

    private static boolean blocked(TrackSpline track, Solid solid) {
        return blocked(track, solid, 20.9, 21.15);
    }

    private static boolean blocked(TrackSpline track, Solid solid, double from, double to) {
        Entity entity = mock(Entity.class);
        World world = world(solid);
        when(entity.getWorld()).thenReturn(world);
        return TrainBlockCollision.blocked(entity, track, from, track, to, REACH);
    }

    private static TrackSpline northTrack() {
        return TrackSpline.fromPoints(UUID.randomUUID(), "world", false,
                List.of(new double[]{0.5, 64, 0}, new double[]{0.5, 64, 100}));
    }

    private static TrackSpline diagonalTrack() {
        return TrackSpline.fromPoints(UUID.randomUUID(), "world", false,
                List.of(new double[]{0.5, 64, 0.5}, new double[]{60.5, 64, 60.5}));
    }

    // Floor at y 63 and walls either side of the three blocks x -1 to 1, rail on x 0.
    private static Solid tunnel(int roofHeight) {
        return (x, y, z) -> y <= 63 || y >= 64 + roofHeight || x < -1 || x > 1;
    }

    private static Solid diagonalTunnel(int wallOffset) {
        return (x, y, z) -> y <= 63 || y >= 67 || Math.abs(x - z) >= wallOffset;
    }

    private static World world(Solid solid) {
        World world = mock(World.class);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            int x = call.getArgument(0);
            int y = call.getArgument(1);
            int z = call.getArgument(2);
            Block block = mock(Block.class);
            boolean full = solid.at(x, y, z);
            when(block.isPassable()).thenReturn(!full);
            when(block.getType()).thenReturn(full ? Material.STONE : Material.AIR);
            if (full) {
                VoxelShape shape = mock(VoxelShape.class);
                when(shape.getBoundingBoxes()).thenReturn(List.of(new BoundingBox(0, 0, 0, 1, 1, 1)));
                when(block.getCollisionShape()).thenReturn(shape);
            }
            return block;
        });
        return world;
    }
}
