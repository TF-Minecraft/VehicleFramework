package net.tfminecraft.vehicleframework.vehicles.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.bukkit.util.VoxelShape;
import org.joml.AxisAngle4d;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.tfminecraft.vehicleframework.bones.BoneRotator;
import net.tfminecraft.vehicleframework.enums.Component;
import net.tfminecraft.vehicleframework.enums.State;
import net.tfminecraft.vehicleframework.enums.VehicleDeath;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.component.Engine;
import net.tfminecraft.vehicleframework.vehicles.component.Wings;
import net.tfminecraft.vehicleframework.vehicles.state.VehicleState;

class LiftControllerHitWallTest {
    // The Monoplane's ModelEngine hitbox (5 x 1.875 x 5) parked on a deck at y 200, as in the 2026-10-27 crash.
    private static final BoundingBox PLANE = new BoundingBox(4238, 200, 2198, 4243, 201.875, 2203);
    // A chest just off the hitbox's north-west corner: inside the contact margin, clear of the hitbox.
    private static final BoundingBox CHEST = new BoundingBox(4237.0625, 200, 2197.0625, 4237.9375, 200.875, 2197.9375);

    private final ActiveVehicle vehicle = mock(ActiveVehicle.class, RETURNS_DEEP_STUBS);
    private final Entity entity = mock(Entity.class);
    private final World world = mock(World.class);
    private final VehicleState state = mock(VehicleState.class);
    private final LiftController lift = new LiftController();

    @BeforeEach
    void setup() {
        when(vehicle.isDestroyed()).thenReturn(false);
        when(vehicle.hasComponent(Component.WINGS)).thenReturn(true);
        when(vehicle.getAccessPanel().getSpeed()).thenReturn(.5);
        when(vehicle.getStateHandler().getCurrentState()).thenReturn(state);
        when(state.getType()).thenReturn(State.FLYING);
        when(vehicle.getEntity()).thenReturn(entity);
        when(entity.getWorld()).thenReturn(world);
        when(entity.getBoundingBox()).thenReturn(PLANE.clone());
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        Block air = block(true);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(air);
    }

    private static Block block(boolean passable, BoundingBox... local) {
        Block block = mock(Block.class);
        when(block.isPassable()).thenReturn(passable);
        VoxelShape shape = mock(VoxelShape.class);
        when(shape.getBoundingBoxes()).thenReturn(List.of(local));
        when(block.getCollisionShape()).thenReturn(shape);
        return block;
    }

    private void place(int x, int y, int z, BoundingBox... local) {
        Block solid = block(false, local);
        when(world.getBlockAt(x, y, z)).thenReturn(solid);
    }

    private void placeChest() {
        place(4237, 200, 2197, new BoundingBox(.0625, 0, .0625, .9375, .875, .9375));
    }

    @Test
    void taxiingIntoAChestHaltsInsteadOfExploding() {
        placeChest();
        when(state.getType()).thenReturn(State.GROUND);
        lift.checkHitWall(vehicle);
        verify(vehicle, never()).kill(any());
    }

    private double liftAfterCheck() {
        Wings wings = mock(Wings.class);
        when(wings.getLift()).thenReturn(6.7);
        Engine engine = mock(Engine.class, RETURNS_DEEP_STUBS);
        when(engine.getThrottle().getCurrent()).thenReturn(100);
        when(vehicle.hasComponent(Component.ENGINE)).thenReturn(true);
        when(vehicle.getComponent(Component.WINGS)).thenReturn(wings);
        when(vehicle.getComponent(Component.ENGINE)).thenReturn(engine);
        BoneRotator rotator = mock(BoneRotator.class);
        when(rotator.getAngles()).thenReturn(new AxisAngle4d());
        lift.checkHitWall(vehicle);
        return lift.calculateLift(rotator, vehicle, new Vector(.7, 0, 0)).getY();
    }

    @Test
    void aPlaneJammedAgainstABlockOnTheGroundCannotLiftOff() {
        placeChest();
        when(state.getType()).thenReturn(State.GROUND);
        assertEquals(-.49, liftAfterCheck(), 1e-9);
    }

    @Test
    void aPlaneClearOfBlocksOnTheGroundStillLiftsOff() {
        when(state.getType()).thenReturn(State.GROUND);
        assertEquals(.03, liftAfterCheck(), 1e-9);
    }

    @Test
    void theGroundBlockClearsOnTheNextCheck() {
        placeChest();
        when(state.getType()).thenReturn(State.GROUND);
        liftAfterCheck();
        when(vehicle.getAccessPanel().getSpeed()).thenReturn(.1);
        assertEquals(.03, liftAfterCheck(), 1e-9);
    }

    @Test
    void flyingIntoTheSameChestStillExplodes() {
        placeChest();
        lift.checkHitWall(vehicle);
        verify(vehicle).kill(VehicleDeath.EXPLODE);
    }

    @Test
    void aVehicleWithNoCurrentStateIsCheckedLikeAFlyingOne() {
        placeChest();
        when(vehicle.getStateHandler().getCurrentState()).thenReturn(null);
        lift.checkHitWall(vehicle);
        verify(vehicle).kill(VehicleDeath.EXPLODE);
    }

    @Test
    void openAirAroundAFlyingPlaneIsNotAHit() {
        lift.checkHitWall(vehicle);
        verify(vehicle, never()).kill(any());
    }

    @Test
    void contactZoneWidensTheSidesAndSkipsTheFloorBand() {
        BoundingBox zone = LiftController.contactZone(PLANE);
        assertEquals(new BoundingBox(4237.5, 200.5, 2197.5, 4243.5, 201.875, 2203.5), zone);
        // A hitbox under a block tall keeps its upper half.
        BoundingBox low = LiftController.contactZone(new BoundingBox(0, 10, 0, 1, 10.6, 1));
        assertEquals(10.3, low.getMinY(), 1e-9);
        assertEquals(10.6, low.getMaxY(), 1e-9);
    }

    @Test
    void touchingTheZoneEdgeIsNotAHit() {
        BoundingBox zone = LiftController.contactZone(PLANE);
        // Whole blocks just past each side: the old rounding up counted these.
        Map<String, BoundingBox> beyond = Map.of(
                "east", new BoundingBox(4243.5, 200, 2200, 4244.5, 201, 2201),
                "south", new BoundingBox(4240, 200, 2203.5, 4241, 201, 2204.5),
                "above", new BoundingBox(4240, 201.875, 2200, 4241, 202.875, 2201));
        for (Map.Entry<String, BoundingBox> side : beyond.entrySet()) {
            assertFalse(LiftController.touchesSolid(zone, (x, y, z) -> List.of(side.getValue())), side.getKey());
        }
    }

    @Test
    void onlyTheBlocksRealShapeCounts() {
        BoundingBox zone = LiftController.contactZone(PLANE);
        BoundingBox slab = new BoundingBox(4240, 200, 2200, 4241, 200.5, 2201);
        assertFalse(LiftController.touchesSolid(zone, (x, y, z) -> x == 4240 && y == 200 && z == 2200 ? List.of(slab) : List.of()));
        BoundingBox fence = new BoundingBox(4240.375, 200, 2200.375, 4240.625, 201.5, 2200.625);
        assertTrue(LiftController.touchesSolid(zone, (x, y, z) -> x == 4240 && y == 200 && z == 2200 ? List.of(fence) : List.of()));
    }

    @Test
    void touchesSolidVisitsEachBlockTheZoneReachesEdgesIncluded() {
        BoundingBox zone = new BoundingBox(.5, 0.5, .5, 2.0, 1.0, 2.0);
        List<String> visited = new java.util.ArrayList<>();
        LiftController.touchesSolid(zone, (x, y, z) -> { visited.add(x + "," + y + "," + z); return List.of(); });
        assertEquals(List.of("0,0,0", "0,0,1", "0,0,2", "0,1,0", "0,1,1", "0,1,2",
                "1,0,0", "1,0,1", "1,0,2", "1,1,0", "1,1,1", "1,1,2",
                "2,0,0", "2,0,1", "2,0,2", "2,1,0", "2,1,1", "2,1,2"), visited);
    }

    @Test
    void solidBoxesShiftsTheShapeIntoTheWorld() {
        place(4237, 200, 2197, new BoundingBox(.0625, 0, .0625, .9375, .875, .9375));
        assertEquals(List.of(new BoundingBox(4237.0625, 200, 2197.0625, 4237.9375, 200.875, 2197.9375)),
                LiftController.solidBoxes(world, 4237, 200, 2197));
    }

    @Test
    void solidBoxesSkipsPassableBlocks() {
        assertEquals(List.of(), LiftController.solidBoxes(world, 4240, 200, 2200));
    }

    @Test
    void solidBoxesNeverLoadsAChunk() {
        when(world.isChunkLoaded(265, 137)).thenReturn(false);
        assertEquals(List.of(), LiftController.solidBoxes(world, 4240, 200, 2200));
        verify(world).isChunkLoaded(265, 137);
        verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
    }

    @Test
    void theChestFromTheCrashSitsInsideTheFlyingContactZone() {
        assertTrue(CHEST.overlaps(LiftController.contactZone(PLANE)));
    }
}
