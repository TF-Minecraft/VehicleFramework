package net.tfminecraft.vehicleframework.vehicles.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.*;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.bukkit.util.VoxelShape;
import org.junit.jupiter.api.*;
import org.mockito.*;
import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.bone.ModelBone;
import net.tfminecraft.vehicleframework.VFLogger;
import net.tfminecraft.vehicleframework.bones.VectorBone;
import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.enums.Component;
import net.tfminecraft.vehicleframework.enums.Direction;
import net.tfminecraft.vehicleframework.enums.State;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.component.Harness;
import net.tfminecraft.vehicleframework.vehicles.handlers.state.TerrainFollowConfig;

class TerrainFollowEngineCoverageTest {
    private final World world = mock(World.class);
    private final LivingEntity entity = mock(LivingEntity.class);
    private final ActiveVehicle vehicle = mock(ActiveVehicle.class, RETURNS_DEEP_STUBS);
    private final ActiveModel model = mock(ActiveModel.class);
    private final VectorBone vector = mock(VectorBone.class);
    private final BaseController base = new BaseController();
    private final Map<Cell, TerrainBlock> blocks = new LinkedHashMap<>();
    private final Map<String, ModelBone> bones = new HashMap<>();
    private final List<String> messages = new ArrayList<>();
    private MockedStatic<GroundEngineLog> engineLog;
    private MockedStatic<VFLogger> log;
    private Location location;
    private Vector velocity;
    private double locationOffset;
    private double halfWidth = .4;
    private double height = 1;
    private boolean oldDebug;
    private TerrainFollowConfig config;

    @BeforeEach
    void setup() {
        oldDebug = Cache.terrainFollowDebug;
        Cache.terrainFollowDebug = false;
        engineLog = mockStatic(GroundEngineLog.class);
        log = mockStatic(VFLogger.class);
        engineLog.when(() -> GroundEngineLog.formatEngineFragment(vehicle)).thenReturn("");
        engineLog.when(() -> GroundEngineLog.append(anyString())).thenAnswer(call -> { messages.add(call.getArgument(0)); return null; });
        when(world.getName()).thenReturn("terrain");
        location = new Location(world, .5, 64, .5, 25, 6);
        velocity = new Vector();
        config = config(List.of());
        when(vehicle.getEntity()).thenReturn(entity);
        when(vehicle.getUUID()).thenReturn(UUID.randomUUID().toString());
        when(vehicle.getId()).thenReturn("test_car");
        when(vehicle.getModel()).thenReturn(model);
        when(vehicle.getCurrentState().getType()).thenReturn(State.GROUND);
        when(entity.isValid()).thenReturn(true);
        when(entity.getWorld()).thenReturn(world);
        when(entity.getLocation()).thenAnswer(call -> location.clone());
        when(entity.getVelocity()).thenAnswer(call -> velocity.clone());
        when(entity.getBoundingBox()).thenAnswer(call -> new BoundingBox(location.getX() - halfWidth,
                location.getY() - locationOffset, location.getZ() - halfWidth,
                location.getX() + halfWidth, location.getY() - locationOffset + height, location.getZ() + halfWidth));
        when(vector.getVector()).thenAnswer(call -> new Vector(1, 0, 0));
        when(model.getBone(anyString())).thenAnswer(call -> Optional.ofNullable(bones.get(call.getArgument(0))));
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> terrain(call.getArgument(0), call.getArgument(1), call.getArgument(2)).block);
        when(world.rayTraceBlocks(any(Location.class), any(Vector.class), anyDouble(), any(FluidCollisionMode.class), anyBoolean()))
                .thenAnswer(call -> trace(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3)));
    }

    @AfterEach
    void teardown() {
        when(entity.isValid()).thenReturn(false);
        TerrainFollowEngine.step(vehicle, vector, Direction.STILL, base, config);
        log.close();
        engineLog.close();
        Cache.terrainFollowDebug = oldDebug;
    }

    private TerrainFollowConfig config(List<String> probes) {
        return new TerrainFollowConfig(true, 1, .25, 3, 1, .08, .98, probes);
    }
    private TerrainFollowEngine.Result step() { return step(Direction.FORWARD); }
    private TerrainFollowEngine.Result step(Direction direction) {
        return TerrainFollowEngine.step(vehicle, vector, direction, base, config);
    }
    private void apply(TerrainFollowEngine.Result result) {
        location = result.location.clone();
        velocity = result.velocity.clone();
    }
    private TerrainBlock terrain(int x, int y, int z) {
        return blocks.computeIfAbsent(new Cell(x, y, z), TerrainBlock::new);
    }
    private TerrainBlock solid(int x, int y, int z) { return solid(x, y, z, new BoundingBox(0, 0, 0, 1, 1, 1)); }
    private TerrainBlock solid(int x, int y, int z, BoundingBox... shapes) {
        TerrainBlock block = terrain(x, y, z);
        block.boxes = List.of(shapes);
        return block;
    }
    private void floor(int y) {
        for (int x = -3; x <= 15; x++) for (int z = -3; z <= 3; z++) solid(x, y, z);
    }
    private void bone(String id, Location at) {
        ModelBone bone = mock(ModelBone.class);
        when(bone.getLocation()).thenAnswer(call -> at == null ? null : at.clone());
        bones.put(id, bone);
    }
    private Player viewer(World in, boolean online) {
        Player player = mock(Player.class);
        when(player.getWorld()).thenReturn(in);
        when(player.isOnline()).thenReturn(online);
        return player;
    }
    private RayTraceResult hit(Block block, double x, double y, double z) {
        return new RayTraceResult(new Vector(x, y, z), block, BlockFace.UP);
    }
    private RayTraceResult trace(Location start, Vector direction, double maxDistance, FluidCollisionMode fluids) {
        RayTraceResult nearest = null;
        double best = Double.POSITIVE_INFINITY;
        for (TerrainBlock block : blocks.values()) {
            List<BoundingBox> boxes = block.boxes;
            if (fluids == FluidCollisionMode.ALWAYS && block.liquid && boxes.isEmpty())
                boxes = List.of(new BoundingBox(0, 0, 0, 1, 1, 1));
            for (BoundingBox local : boxes) {
                BoundingBox shape = local.clone().shift(block.cell.x, block.cell.y, block.cell.z);
                RayTraceResult result = shape.rayTrace(start.toVector(), direction, maxDistance);
                if (result == null) continue;
                double distance = result.getHitPosition().distanceSquared(start.toVector());
                if (distance < best) {
                    best = distance;
                    nearest = new RayTraceResult(result.getHitPosition(), block.block, result.getHitBlockFace());
                }
            }
        }
        return nearest;
    }

    @Test
    void invalidEntitiesReturnNoMovementAndDiscardAirborneMomentum() {
        when(entity.isValid()).thenReturn(false);
        assertNull(step());
        when(vehicle.getEntity()).thenReturn(null);
        when(vehicle.getUUID()).thenReturn(null);
        assertNull(step());
    }

    @Test
    void flatGroundKeepsFootOffsetYawAndPitchAndDoesNotMutateEntityVelocity() {
        floor(63);
        locationOffset = .3;
        location.setY(64.3);
        velocity = new Vector(.6, -.4, .2);
        TerrainFollowEngine.Result result = step();
        assertEquals(1.1, result.location.getX(), 1e-9);
        assertEquals(64.3, result.location.getY(), 1e-9);
        assertEquals(.7, result.location.getZ(), 1e-9);
        assertEquals(25, result.location.getYaw());
        assertEquals(6, result.location.getPitch());
        assertEquals(new Vector(.6, 0, .2), result.velocity);
        assertEquals(new Vector(.6, -.4, .2), velocity);
        assertNull(result.tilt);
    }

    @Test
    void stationaryAndFastVehiclesRemainOnGroundWithoutVerticalInterpolation() {
        floor(63);
        TerrainFollowEngine.Result stationary = step();
        assertEquals(location, stationary.location);
        assertEquals(new Vector(), stationary.velocity);
        velocity = new Vector(2, 0, 0);
        TerrainFollowEngine.Result fast = step();
        assertEquals(2.5, fast.location.getX());
        assertEquals(new Vector(2, 0, 0), fast.velocity);
    }

    @Test
    void downhillDropsDirectlyToSupportInsteadOfLimitingByClimbSnap() {
        floor(63);
        location.setY(65.5);
        velocity = new Vector(.4, 0, 0);
        TerrainFollowEngine.Result result = step();
        assertEquals(64, result.location.getY());
        assertEquals(.9, result.location.getX(), 1e-9);
        assertEquals(0, result.velocity.getY());
    }

    @Test
    void shallowStepRaisesThenContinuesForwardWithoutPenetratingItsShape() {
        floor(63);
        solid(1, 64, 0, new BoundingBox(0, 0, 0, 1, .5, 1));
        velocity = new Vector(.6, 0, 0);
        TerrainFollowEngine.Result result = step();
        assertEquals(64.5, result.location.getY(), 1e-9);
        assertEquals(1.1, result.location.getX(), 1e-9);
        assertTrue(result.location.getY() >= 64.5);
    }

    @Test
    void tallObstacleStopsForwardMotionAndRetainsHorizontalMomentumForLaterFall() {
        floor(63);
        solid(1, 64, 0);
        solid(1, 65, 0);
        config = new TerrainFollowConfig(true, .5, .25, 0, 0, .08, .98, List.of());
        velocity = new Vector(.6, 0, 0);
        when(vehicle.hasComponent(Component.ENGINE)).thenReturn(true);
        when(vehicle.getAccessPanel().getSpeed()).thenReturn(.6);
        TerrainFollowEngine.Result result = step();
        assertEquals(.5, result.location.getX());
        assertEquals(new Vector(), result.velocity);
        blocks.clear();
        apply(result);
        step();
        TerrainFollowEngine.Result airborne = step();
        assertTrue(airborne.velocity.getX() > 0, "Stored drive momentum should survive leaving blocked ground");
    }

    @Test
    void reportedForwardHitWithoutCollisionShapeConservativelyStopsAtBlockTop() {
        floor(63);
        Block reported = terrain(1, 64, 0).block;
        RayTraceResult hit = hit(reported, 1, 64.08, .5);
        when(world.rayTraceBlocks(any(Location.class), any(Vector.class), anyDouble(),
                eq(FluidCollisionMode.NEVER), eq(true))).thenReturn(hit);
        config = new TerrainFollowConfig(true, .5, .25, 0, 0, .08, .98, List.of());
        velocity = new Vector(.6, 0, 0);
        TerrainFollowEngine.Result result = step();
        assertEquals(.5, result.location.getX());
        assertEquals(64, result.location.getY());
        assertEquals(new Vector(), result.velocity);
    }

    @Test
    void lookaheadRaisesSupportBeforeWheelsReachHigherGround() {
        floor(63);
        solid(2, 64, 0, new BoundingBox(0, 0, 0, 1, .5, 1));
        velocity = new Vector(.5, 0, 0);
        config = new TerrainFollowConfig(true, 1, .1, 4, 0, .08, .98, List.of());
        TerrainFollowEngine.Result result = step();
        assertEquals(64.1, result.location.getY(), 1e-9);
        assertEquals(1, result.location.getX(), 1e-9);
    }

    @Test
    void fourWheelContactsDeterminePitchRollAndHighestSupport() {
        location = new Location(world, 1, 65, 1);
        bone("fl", new Location(world, .5, 65, 1.5));
        bone("fr", new Location(world, 1.5, 65, 1.5));
        bone("bl", new Location(world, .5, 65, .5));
        bone("br", new Location(world, 1.5, 65, .5));
        solid(0, 64, 1, new BoundingBox(0, 0, 0, 1, .4, 1));
        solid(1, 64, 1, new BoundingBox(0, 0, 0, 1, .6, 1));
        solid(0, 63, 0);
        solid(1, 64, 0, new BoundingBox(0, 0, 0, 1, .2, 1));
        config = config(List.of("fl", "fr", "bl", "br"));
        TerrainFollowEngine.Result result = step();
        assertEquals(64.6, result.location.getY(), 1e-9);
        assertNotNull(result.tilt);
        assertEquals(-21.8014, result.tilt.pitchDeg, .001);
        assertEquals(-11.3099, result.tilt.rollDeg, .001);
    }

    @Test
    void missingWheelContactKeepsSupportButDoesNotInventTilt() {
        floor(63);
        config = config(List.of("fl", "fr", "bl", "br"));
        bone("fl", new Location(world, .1, 64, .1));
        bone("fr", new Location(world, .9, 64, .1));
        bone("bl", new Location(world, .1, 64, .9));
        bone("br", new Location(world, 50, 64, 50));
        TerrainFollowEngine.Result result = step();
        assertEquals(64, result.location.getY());
        assertNull(result.tilt);
        velocity = new Vector(.2, 0, 0);
        assertEquals(.7, step().location.getX(), 1e-9);
    }

    @Test
    void twoMissedSupportTicksStartGravityAndAirDragThenLandingResetsFall() {
        floor(63);
        velocity = new Vector(.5, 0, 0);
        apply(step());
        blocks.clear();
        TerrainFollowEngine.Result firstMiss = step();
        assertEquals(63.92, firstMiss.location.getY(), 1e-9);
        assertEquals(0, firstMiss.velocity.getY());
        apply(firstMiss);
        TerrainFollowEngine.Result airborne = step();
        assertEquals(-.08, airborne.velocity.getY(), 1e-9);
        assertEquals(.49, airborne.velocity.getX(), 1e-9);
        apply(airborne);
        TerrainFollowEngine.Result next = step();
        assertEquals(-.16, next.velocity.getY(), 1e-9);
        assertEquals(.4802, next.velocity.getX(), 1e-9);
        apply(next);
        floor(62);
        TerrainFollowEngine.Result landed = step();
        assertEquals(63, landed.location.getY());
        assertEquals(0, landed.velocity.getY());
        assertNull(landed.tilt);
    }

    @Test
    void fallingWithoutHorizontalMomentumAndBlockedDownwardMotionRemainFinite() {
        step();
        apply(step());
        TerrainFollowEngine.Result falling = step();
        assertEquals(0, falling.velocity.getX());
        assertEquals(-.16, falling.velocity.getY(), 1e-9);
        // Side support lies under the chassis but outside the central downward ray.
        location = new Location(world, .95, 64, .5);
        solid(1, 63, 0);
        TerrainFollowEngine.Result blocked = step();
        assertTrue(blocked.location.getY() >= 63.92 - 1e-9);
        assertTrue(blocked.location.getY() <= 64);
        assertEquals(0, blocked.velocity.getY());
    }

    @Test
    void airborneMotionCannotTunnelThroughWallWhenEndpointIsClear() {
        floor(63);
        velocity = new Vector(3, 0, 0);
        apply(step());
        blocks.clear();
        apply(step());
        assertEquals(6.5, location.getX(), 1e-9);
        solid(8, 63, 0);
        solid(8, 64, 0);
        solid(8, 65, 0);
        TerrainFollowEngine.Result result = step();
        assertTrue(result.location.getX() <= 7.65 + 1e-9,
                "A wall between start and endpoint must stop airborne travel; x=" + result.location.getX());
        assertTrue(result.velocity.getX() < 2.94);
    }

    @Test
    void airborneMovementStopsBeforeWallWhoseEndpointWouldOverlap() {
        floor(63);
        velocity = new Vector(.5, 0, 0);
        apply(step());
        blocks.clear();
        apply(step());
        solid(2, 63, 0);
        solid(2, 64, 0);
        TerrainFollowEngine.Result result = step();
        assertTrue(result.location.getX() <= 1.65 + 1e-9);
        assertTrue(result.velocity.getX() < .49);
    }

    @Test
    void fastFallCannotTunnelThroughPlatformBeyondTheShortGroundProbe() {
        location.setY(300);
        floor(155);
        for (int i = 0; i < 60; i++) apply(step());
        assertEquals(158.32, location.getY(), 1e-8);
        TerrainFollowEngine.Result result = step();
        assertTrue(result.location.getY() >= 155.92 - 1e-9,
                "A falling chassis must stay above the platform crossed this tick; y=" + result.location.getY());
    }

    @Test
    void alreadyOverlappingAirborneVehicleDoesNotMoveFurtherIntoObstacle() {
        step();
        step();
        solid(0, 64, 0);
        bone("body", new Location(world, 20, 64, 20));
        TerrainFollowEngine.Result result = step();
        assertEquals(location, result.location);
        assertEquals(new Vector(), result.velocity);
    }

    @Test
    void climbUnsticksShallowEmbeddingAndPrefersResolvedMovementWhenClear() {
        solid(0, 64, 0, new BoundingBox(0, 0, 0, 1, .25, 1));
        engineLog.when(GroundEngineLog::isEnabled).thenReturn(true);
        TerrainFollowEngine.Result result = step();
        assertEquals(64.25, result.location.getY());
        assertTrue(messages.stream().anyMatch(m -> m.contains("resolve=unstick+up")));
    }

    @Test
    void climbUnstickCanRecoverWhenNormalSnapStillIntersectsGround() {
        solid(0, 64, 0, new BoundingBox(0, 0, 0, 1, .5, 1));
        bone("body", new Location(world, .5, 64.5, .5));
        engineLog.when(GroundEngineLog::isEnabled).thenReturn(true);
        TerrainFollowEngine.Result result = step();
        assertEquals(64.45, result.location.getY(), 1e-9);
        assertTrue(messages.stream().anyMatch(m -> m.contains("resolve=unstick ")));
    }

    @Test
    void reverseDoesNotUnstickIntoHigherGround() {
        solid(0, 64, 0, new BoundingBox(0, 0, 0, 1, .5, 1));
        bone("body", new Location(world, .5, 64.5, .5));
        TerrainFollowEngine.Result result = step(Direction.BACKWARD);
        assertEquals(64, result.location.getY());
    }

    @Test
    void probesIgnoreMissingEmptyAndWorldlessBonesAndLogMissingNameOnlyOnce() {
        config = config(Arrays.asList(null, " ", "missing", "no-location", "no-world", "wheel"));
        bone("no-location", null);
        bone("no-world", new Location(null, 0, 0, 0));
        Location wheel = new Location(world, 1, 64, 2);
        bone("wheel", wheel);
        List<Location> starts = TerrainFollowEngine.resolveProbeStarts(vehicle, config);
        assertEquals(List.of(new Location(world, 1, 64.2, 2)), starts);
        assertEquals(64, wheel.getY());
        TerrainFollowEngine.resolveProbeStarts(vehicle, config);
        log.verify(() -> VFLogger.log("Vehicle test_car ground-probe bone 'missing' is missing, skipping"), times(1));
        assertTrue(TerrainFollowEngine.resolveProbeStarts(vehicle, null).isEmpty());
        assertTrue(TerrainFollowEngine.resolveProbeStarts(vehicle, config(List.of())).isEmpty());
        when(vehicle.getModel()).thenReturn(null);
        assertTrue(TerrainFollowEngine.resolveProbeStarts(vehicle, config).isEmpty());
    }

    @Test
    void bodySamplingUsesUsableBodyBoneAndFallsBackToEntityLocation() {
        Location fallback = TerrainFollowEngine.bodyDownStart(vehicle, location, config);
        assertEquals(new Location(world, .5, 64.5, .5, 25, 6), fallback);
        bone("body", new Location(world, .7, 64.7, .8));
        assertEquals(new Location(world, .7, 64.9, .8), TerrainFollowEngine.bodyDownStart(vehicle, location, config));
        bone("body", null);
        assertEquals(fallback, TerrainFollowEngine.bodyDownStart(vehicle, location, config));
        bone("body", new Location(null, 0, 0, 0));
        assertEquals(fallback, TerrainFollowEngine.bodyDownStart(vehicle, location, config));
        when(vehicle.getModel()).thenReturn(null);
        assertEquals(fallback, TerrainFollowEngine.bodyDownStart(vehicle, location, config));
    }

    @Test
    void liquidsAndWaterloggedBlocksNeverSupportGroundVehicles() {
        TerrainBlock water = terrain(0, 63, 0);
        water.liquid = true;
        assertFalse(TerrainFollowEngine.isValidGroundHit(hit(water.block, .5, 64, .5)));
        assertEquals(63.92, step().location.getY(), 1e-9);
        water.liquid = false;
        Waterlogged data = mock(Waterlogged.class);
        when(water.block.getBlockData()).thenReturn(data);
        when(data.isWaterlogged()).thenReturn(true);
        assertFalse(TerrainFollowEngine.isValidGroundHit(hit(water.block, .5, 64, .5)));
        when(data.isWaterlogged()).thenReturn(false);
        assertTrue(TerrainFollowEngine.isValidGroundHit(hit(water.block, .5, 64, .5)));
        assertFalse(TerrainFollowEngine.isValidGroundHit(null));
        assertFalse(TerrainFollowEngine.isValidGroundHit(new RayTraceResult(new Vector())));
        RayTraceResult incomplete = mock(RayTraceResult.class);
        when(incomplete.getHitBlock()).thenReturn(water.block);
        assertFalse(TerrainFollowEngine.isValidGroundHit(incomplete));
    }

    @Test
    void debugParticlesReachOnlyOnlinePlayersInTheSameWorld() {
        floor(63);
        Cache.terrainFollowDebug = true;
        when(vehicle.getNearbyPlayers()).thenReturn(List.of());
        step();
        when(vehicle.getNearbyPlayers()).thenReturn(null);
        step();
        Player online = viewer(world, true), offline = viewer(world, false);
        Player otherWorld = viewer(mock(World.class), true), noWorld = viewer(null, true);
        when(vehicle.getNearbyPlayers()).thenReturn(Arrays.asList(null, offline, otherWorld, noWorld, online));
        step();
        verify(online).spawnParticle(Particle.END_ROD, new Location(world, .5, 64.5, .5, 25, 6), 1, 0, 0, 0, 0);
        verify(online).spawnParticle(Particle.END_ROD, new Location(world, .5, 64, .5), 1, 0, 0, 0, 0);
        verify(offline, never()).spawnParticle(any(Particle.class), any(Location.class), anyInt(), anyDouble(), anyDouble(), anyDouble(), anyDouble());
        verify(otherWorld, never()).spawnParticle(any(Particle.class), any(Location.class), anyInt(), anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    void loggingIncludesStateDirectionSupportAndEngineFragment() {
        floor(63);
        engineLog.when(GroundEngineLog::isEnabled).thenReturn(true);
        engineLog.when(() -> GroundEngineLog.formatEngineFragment(vehicle)).thenReturn("thr=42/0/100");
        velocity = new Vector(.2, 0, 0);
        step();
        assertTrue(messages.getLast().contains("state=GROUND dir=FORWARD"));
        assertTrue(messages.getLast().contains("lookaheadY=64.000"));
        assertTrue(messages.getLast().endsWith("thr=42/0/100"));
        when(vehicle.getCurrentState()).thenReturn(null);
        velocity = new Vector();
        step(null);
        assertTrue(messages.getLast().contains("state=null dir=null"));
        assertTrue(messages.getLast().contains("lookaheadY=nan"));
    }

    @Test
    void realBaseControllerSuppliesEngineAndHarnessHorizontalDrive() {
        floor(63);
        when(vehicle.hasComponent(Component.ENGINE)).thenReturn(true);
        when(vehicle.getAccessPanel().getSpeed()).thenReturn(.7);
        assertEquals(1.2, step().location.getX(), 1e-9);
        when(vehicle.hasComponent(Component.ENGINE)).thenReturn(false);
        when(vehicle.hasComponent(Component.HARNESS)).thenReturn(true);
        Harness harness = mock(Harness.class);
        when(vehicle.getComponent(Component.HARNESS)).thenReturn(harness);
        when(harness.hasMounts()).thenReturn(true);
        assertEquals(Direction.MOVING, base.getDirection(vehicle));
        when(harness.getSpeed()).thenReturn(1d);
        assertEquals(1.5, step(Direction.FORWARD).location.getX(), 1e-9);
        assertEquals(.2, step(Direction.BACKWARD).location.getX(), 1e-9);
        when(harness.hasMounts()).thenReturn(false);
        assertEquals(Direction.STILL, base.getDirection(vehicle));
        assertEquals(.5, step().location.getX());
    }

    @Test
    void optionalPhysicsInputsHaveNeutralResults() {
        assertNull(TerrainFollowMath.bodySampleOrigin(List.of(), null));
        assertEquals(0, TerrainFollowMath.farthestUnblocked(0, .02, distance -> false));
        assertEquals(0, TerrainFollowMath.farthestUnblocked(1, .02, null));
        assertEquals(new Vector(), TerrainFollowMath.flattenHorizontal(null));
        assertTrue(Double.isNaN(TerrainFollowMath.supportY(null, new boolean[] {true})));
        assertTrue(Double.isNaN(TerrainFollowMath.supportY(new double[] {64}, null)));
        TerrainFollowMath.Tilt tilt = TerrainFollowMath.tiltFromWorldHits(null, new Vector(), new Vector(), new Vector());
        assertEquals(0, tilt.pitchDeg);
        assertEquals(0, tilt.rollDeg);
    }

    @Test
    void movementWithoutCollisionProviderCanSlideAndClimb() {
        TerrainFollowMath.KinematicMove flat = TerrainFollowMath.raiseThenSlide(.5, .2, 64, 64, 64,
                Double.NaN, .25, null);
        assertEquals(.5, flat.offsetX);
        assertEquals(.2, flat.offsetZ);
        assertEquals("slide", flat.path);
        assertFalse(flat.aabbBlocked);
        TerrainFollowMath.KinematicMove climb = TerrainFollowMath.raiseThenSlide(.5, .2, 64, 64.25, 64.25,
                Double.NaN, .25, null);
        assertEquals(64.25, climb.y);
        assertEquals("up+slide", climb.path);
    }

    @Test
    void collisionAtDiagonalCornerUsesClearAxesWithoutCrossingObstacle() {
        TerrainFollowMath.OffsetYBlocked corner = (x, z, y) -> x > 0 && x < .4 && z > 0 && z < .4;
        TerrainFollowMath.KinematicMove flat = TerrainFollowMath.raiseThenSlide(1, 1, 64, 64, 64,
                Double.NaN, .25, corner);
        assertEquals(1, flat.offsetX);
        assertEquals(1, flat.offsetZ);
        assertEquals("slideX+slideZ", flat.path);
        assertFalse(flat.aabbBlocked);
        TerrainFollowMath.KinematicMove raised = TerrainFollowMath.raiseThenSlide(1, 1, 64, 64.25, 64.25,
                64.25, .25, corner);
        assertEquals(64.25, raised.y);
        assertEquals("up+slideX+slideZ", raised.path);
        assertFalse(raised.aabbBlocked);
    }

    @Test
    void crampedBackingRecoveryUsesOnlyAvailableClearDistance() {
        TerrainFollowMath.OffsetYBlocked cramped = (x, z, y) -> y > 64 || x > 0 || x < -.07;
        TerrainFollowMath.KinematicMove move = TerrainFollowMath.raiseThenSlide(.5, 0, 64, 64, 64,
                65, .25, cramped);
        assertEquals(-.06, move.offsetX, 1e-9);
        assertEquals(64, move.y);
        assertEquals("back", move.path);
        assertTrue(move.aabbBlocked);
    }

    private record Cell(int x, int y, int z) {}
    private final class TerrainBlock {
        final Cell cell;
        final Block block = mock(Block.class);
        List<BoundingBox> boxes = List.of();
        boolean liquid;
        TerrainBlock(Cell cell) {
            this.cell = cell;
            VoxelShape shape = mock(VoxelShape.class);
            when(shape.getBoundingBoxes()).thenAnswer(call -> boxes);
            when(block.getCollisionShape()).thenReturn(shape);
            when(block.isPassable()).thenAnswer(call -> boxes.isEmpty());
            when(block.isLiquid()).thenAnswer(call -> liquid);
            when(block.getY()).thenReturn(cell.y);
            when(block.getLocation()).thenAnswer(call -> new Location(world, cell.x, cell.y, cell.z));
        }
    }
}
