package net.tfminecraft.vehicleframework.vehicles.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.Optional;

import org.bukkit.Location;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import com.ticxo.modelengine.api.model.bone.SimpleManualAnimator;
import com.ticxo.modelengine.api.model.bone.ModelBone;
import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.generator.blueprint.BlueprintBone;

import net.tfminecraft.vehicleframework.VehicleFramework;
import net.tfminecraft.vehicleframework.enums.Direction;
import net.tfminecraft.vehicleframework.bones.BoneRotator;
import net.tfminecraft.vehicleframework.database.ConsistData;
import net.tfminecraft.vehicleframework.database.VehicleRepository;
import net.tfminecraft.vehicleframework.database.VehicleSnapshot;
import net.tfminecraft.vehicleframework.tracks.TrackJunction;
import net.tfminecraft.vehicleframework.tracks.TrackPose;
import net.tfminecraft.vehicleframework.tracks.TrackRegistry;
import net.tfminecraft.vehicleframework.tracks.TrackSpline;
import net.tfminecraft.vehicleframework.tracks.TrackStore;
import net.tfminecraft.vehicleframework.tracks.TrainCollision;
import net.tfminecraft.vehicleframework.tracks.TrainCollisionWarning;
import net.tfminecraft.vehicleframework.tracks.TrainPath;
import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.enums.VehicleDeath;
import org.bukkit.entity.Player;
import java.util.Map;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.component.propulsion.Throttle;
import net.tfminecraft.vehicleframework.vehicles.controller.VehicleMovementController;
import net.tfminecraft.vehicleframework.vehicles.handlers.train.Connector;
import net.tfminecraft.vehicleframework.vehicles.util.AccessPanel;

class TrainReversePlacementTest {
    @TempDir Path directory;
    private Field registryField;
    private Object previousRegistry;
    private TrackRegistry registry;
    private TrackStore store;
    private MockedStatic<Bukkit> bukkit;

    @ParameterizedTest
    @ValueSource(doubles = {10, 20, 40})
    void couplersStayTogetherThroughBendsStopsAndReverse(double radius) {
        List<double[]> points = new ArrayList<>();
        for (int i = 0; i <= 180; i++) {
            double angle = Math.toRadians(i);
            points.add(new double[]{radius * Math.cos(angle), 64, radius * Math.sin(angle)});
        }
        TrackSpline track = TrackSpline.fromPoints(UUID.randomUUID(), "world", false, points);
        store.save(track);
        registry.loadFromDisk();
        TrainHandler loco = consist(track, track.length() * 0.75);
        List<float[]> rotations = new ArrayList<>();
        for (TrainHandler car = loco; car != null; car = car.hasChild() ? car.getChild().getTrainHandler() : null) {
            float[] rotation = new float[2];
            rotations.add(rotation);
            BoneRotator rotator = stub(BoneRotator.class);
            doAnswer(call -> {
                rotation[0] = call.getArgument(0);
                rotation[1] = call.getArgument(1);
                return true;
            }).when(rotator).rotateToTarget(org.mockito.ArgumentMatchers.anyFloat(),
                    org.mockito.ArgumentMatchers.anyFloat(), org.mockito.ArgumentMatchers.anyFloat(),
                    org.mockito.ArgumentMatchers.anyFloat(), org.mockito.ArgumentMatchers.anyBoolean(),
                    org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean());
            BehaviourHandler behaviour = stub(BehaviourHandler.class);
            when(behaviour.getRotator()).thenReturn(rotator);
            when(car.v.getBehaviourHandler()).thenReturn(behaviour);
        }
        for (double speed : new double[]{0, 0.72, 0.72, 0, -0.72, -0.72, 0}) {
            loco.v.getAccessPanel().setSpeed(speed);
            loco.splineTick();
            TrainHandler parent = loco;
            for (int i = 0; i < 2; i++) {
                TrainHandler child = parent.getChild().getTrainHandler();
                Vector back = modelAnchor(parent, rotations.get(i), -5);
                Vector front = modelAnchor(child, rotations.get(i + 1), 5);
                assertEquals(0, back.distance(front), 1e-5, "Couplers separated on a bend");
                parent = child;
            }
        }
    }

    private static Vector modelAnchor(TrainHandler car, float[] rotation, double z) {
        return new Vector(0, 0, z).rotateAroundX(Math.toRadians(rotation[1]))
                .rotateAroundY(Math.toRadians(rotation[0]))
                .add(car.v.getEntity().getLocation().toVector());
    }

    @BeforeEach
    void setUp() throws Exception {
        // Default track particles ask Paper to construct BlockData, even in a physics-only fixture.
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.createBlockData(any(Material.class))).thenReturn(mock(BlockData.class));
        registryField = VehicleFramework.class.getDeclaredField("trackRegistry");
        registryField.setAccessible(true);
        previousRegistry = registryField.get(null);
        registry = new TrackRegistry(directory.toFile());
        store = new TrackStore(directory.toFile());
        registryField.set(null, registry);
    }

    @AfterEach
    void restoreRegistry() throws Exception {
        registryField.set(null, previousRegistry);
        bukkit.close();
    }

    @Test
    void forwardStopReverseKeepsBothCarsOnTheirCoupledSide() {
        TrackSpline track = straightTrack(false);
        TrainHandler loco = consist(track, 60);
        assertSequence(loco, track, new double[]{60.2, 60.2, 60.19, 60.19, 60.39});
    }

    @ParameterizedTest
    @CsvSource({"true, false", "false, true", "true, true"})
    void missingConnectorBlueprintKeepsSampledPoseAndRecovers(boolean missingBack, boolean missingFront) {
        TrackSpline track = straightTrack(false);
        TrainHandler loco = consist(track, 60);
        TrainHandler car = loco.getChild().getTrainHandler();
        ModelBone back = loco.v.getModel().getBone("back").orElseThrow();
        ModelBone front = car.v.getModel().getBone("front").orElseThrow();
        BlueprintBone backBlueprint = back.getBlueprintBone();
        BlueprintBone frontBlueprint = front.getBlueprintBone();
        if (missingBack) when(back.getBlueprintBone()).thenReturn(null);
        if (missingFront) when(front.getBlueprintBone()).thenReturn(null);

        loco.placeLoadedCars();
        loco.splineTick();
        double expected = 60 - (missingBack ? 0 : 5) - (missingFront ? 0 : 5);
        assertEquals(expected, car.getS(), 1e-8);
        assertEquals(track.sampleAt(expected).x, car.v.getEntity().getLocation().getX(), 1e-8);
        assertEquals(track.sampleAt(expected).z, car.v.getEntity().getLocation().getZ(), 1e-8);

        when(back.getBlueprintBone()).thenReturn(backBlueprint);
        when(front.getBlueprintBone()).thenReturn(frontBlueprint);
        loco.placeLoadedCars();
        assertPositions(loco, track, 60);
    }

    @Test
    void reversalAcrossLoopSeamKeepsSpacingAndOrder() {
        TrackSpline track = straightTrack(true);
        TrainHandler loco = consist(track, 0.1);
        assertSequence(loco, track, new double[]{0.3, 0.3, 0.29, 0.29, 0.49});
    }

    @Test
    void reversingOnBranchDoesNotStackSecondCarOnLocomotive() {
        TrackSpline stem = straightTrack(false);
        TrackSpline branch = TrackSpline.fromPoints(UUID.randomUUID(), "world", false,
                List.of(new double[]{0, 64, 50}, new double[]{100, 64, 50}));
        store.save(branch);
        TrackJunction junction = new TrackJunction(UUID.randomUUID(), stem.getId(), 50,
                1, TrackJunction.Side.LEFT, branch.getId(), true);
        store.saveJunction("world", junction);
        registry.loadFromDisk();
        TrainHandler loco = consist(branch, 30);
        loco.applyConsist(new ConsistData(null, null, branch.getId().toString(), 30d,
                1, junction.id.toString(), true));
        assertSequence(loco, branch, new double[]{30.2, 30.2, 30.19, 30.19, 30.39});
    }

    @Test
    void loadingStoppedReverseTrainKeepsCarsBehindIt() {
        TrackSpline track = straightTrack(false);
        TrainHandler loco = consist(track, 60);
        loco.applyConsist(new ConsistData(null, null, track.getId().toString(), 60d, -1));
        loco.splineTick();
        assertPositions(loco, track, 60);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, -8})
    void wallAtLastCarStopsEntireReversingTrain(double speed) {
        TrackSpline track = straightTrack(false);
        TrainHandler loco = consist(track, 60.5);
        // The last car's back coupler is at 35.5.
        wall(loco, new BoundingBox(-1, 64, 34.5, 1, 68, 35.5));
        loco.v.getAccessPanel().setSpeed(speed);
        loco.splineTick();
        assertPositions(loco, track, 60.5);
        // Keeping reverse held must not gradually compress the coupling gaps.
        loco.v.getAccessPanel().setSpeed(speed);
        loco.splineTick();
        assertPositions(loco, track, 60.5);
        loco.v.getAccessPanel().setSpeed(0.1);
        loco.splineTick();
        assertPositions(loco, track, 60.6);
    }

    @ParameterizedTest
    @CsvSource({"-0.2, false, BACKWARD", "0.2, true, FORWARD", "0.2, false, FORWARD", "-0.2, true, BACKWARD"})
    void wheelsFollowTheWayTheTrainMovesNotTheReverseSwitch(double speed, boolean reverse, String expected) {
        TrackSpline track = straightTrack(false);
        TrainHandler loco = consist(track, 60.5);
        loco.v.getAccessPanel().setSpeed(speed);
        loco.v.getAccessPanel().setReverse(reverse);
        loco.splineTick();
        Direction dir = Direction.valueOf(expected);
        org.mockito.Mockito.verify(loco.v.getMoveControls()).animateMove(dir);
        org.mockito.Mockito.verify(loco.v.getMoveControls(), org.mockito.Mockito.never())
                .animateMove(dir == Direction.FORWARD ? Direction.BACKWARD : Direction.FORWARD);
    }

    @Test
    void forwardLocomotiveWallStopsAllCarsAndAllowsReverse() {
        TrackSpline track = straightTrack(false);
        TrainHandler loco = consist(track, 60.5);
        wall(loco, new BoundingBox(-1, 64, 65.5, 1, 68, 66.5));
        loco.v.getAccessPanel().setSpeed(0.1);
        loco.splineTick();
        assertPositions(loco, track, 60.5);
        loco.v.getAccessPanel().setSpeed(-0.1);
        loco.splineTick();
        assertPositions(loco, track, 60.4);
    }

    @Test
    void fastReverseCannotTunnelThroughWallBetweenTickPositions() {
        TrackSpline track = straightTrack(false);
        TrainHandler loco = consist(track, 60.5);
        wall(loco, new BoundingBox(-1, 64, 30.45, 1, 68, 30.55));
        loco.v.getAccessPanel().setSpeed(-8);
        loco.splineTick();
        TrainHandler last = loco.getChild().getTrainHandler().getChild().getTrainHandler();
        assertTrue(last.getS() - 5 >= 30.55 - 1e-8, "Last car must stop before the thin wall");
        assertPositions(loco, track, loco.getS());
    }

    @Test
    void blockAlreadyInsideTrainDoesNotTrapIt() {
        TrackSpline track = straightTrack(false);
        TrainHandler loco = consist(track, 60.5);
        wall(loco, new BoundingBox(-1, 64, 49, 1, 68, 50));
        loco.v.getAccessPanel().setSpeed(-0.1);
        loco.splineTick();
        assertPositions(loco, track, 60.4);
        loco.v.getAccessPanel().setSpeed(0.1);
        loco.splineTick();
        assertPositions(loco, track, 60.5);
    }

    @Test
    void trackEndAtLastCarDoesNotCompressConsist() {
        TrackSpline track = straightTrack(false);
        TrainHandler loco = consist(track, 20);
        loco.v.getAccessPanel().setSpeed(-0.1);
        loco.splineTick();
        assertPositions(loco, track, 20);
        loco.v.getAccessPanel().setSpeed(0.1);
        loco.splineTick();
        assertPositions(loco, track, 20.1);
    }

    @Test
    void carsAttachedAtTrackStartCanPullAwayUntilSpacingIsRestored() {
        TrackSpline track = straightTrack(false);
        TrainHandler loco = consist(track, 0);
        loco.v.getThrottle().setThrottle(100);
        for (int tick = 1; tick <= 25; tick++) {
            loco.v.getAccessPanel().setSpeed(1);
            loco.splineTick();
            assertEquals(tick, loco.getS(), 1e-8);
            assertEquals(Math.max(0, tick - 10), loco.getChild().getTrainHandler().getS(), 1e-8);
            assertEquals(Math.max(0, tick - 20),
                    loco.getChild().getTrainHandler().getChild().getTrainHandler().getS(), 1e-8);
            assertEquals(100, loco.v.getThrottle().getCurrent());
        }
        assertPositions(loco, track, 25);
    }

    @Test
    void alreadyCompressedCarsCannotBePushedFurtherIntoTrackEnd() {
        TrainHandler loco = consist(straightTrack(false), 5);
        loco.v.getThrottle().setThrottle(-100);
        loco.v.getAccessPanel().setSpeed(-0.1);
        loco.splineTick();
        assertEquals(5, loco.getS(), 1e-8);
        assertEquals(0, loco.v.getThrottle().getCurrent());
    }

    @ParameterizedTest
    @CsvSource({"99.5, 0.5, 100, 100", "20.5, -0.5, -100, 20",
            "100, 0.1, 100, 100", "20, -0.1, -100, 20", "20.05, -0.1, -100, 20.05"})
    void trackEndSetsThrottleToZeroAndAllowsDrivingAway(double start, double speed,
            int throttle, double stoppedS) {
        TrackSpline track = straightTrack(false);
        TrainHandler loco = consist(track, start);
        loco.v.getThrottle().setThrottle(throttle);
        loco.v.getAccessPanel().setSpeed(speed);
        loco.splineTick();
        assertPositions(loco, track, stoppedS);
        assertEquals(0, loco.v.getThrottle().getCurrent());
        assertEquals(0, loco.v.getAccessPanel().getSpeed(), 1e-9);
        loco.splineTick();
        assertPositions(loco, track, stoppedS);

        double away = speed > 0 ? -0.1 : 0.1;
        loco.v.getThrottle().setThrottle(speed > 0 ? -1 : 1);
        loco.v.getAccessPanel().setSpeed(away);
        loco.splineTick();
        assertPositions(loco, track, stoppedS + away);
        assertEquals(speed > 0 ? -1 : 1, loco.v.getThrottle().getCurrent());
    }

    @ParameterizedTest
    @CsvSource({"97.1, 0.2", "97.1, 8", "22.9, -0.2", "22.9, -8"})
    void outerAxlesStopWholeConsistBeforeLeavingRail(double start, double speed) {
        TrackSpline track = straightTrack(false);
        TrainHandler loco = wheeledConsist(track, start);
        loco.v.getThrottle().setThrottle(speed > 0 ? 100 : -100);
        loco.v.getAccessPanel().setSpeed(speed);
        loco.splineTick();
        assertPositions(loco, track, start);
        assertEquals(0, loco.v.getThrottle().getCurrent());
        assertEquals(0, loco.v.getAccessPanel().getSpeed(), 1e-9);
        // Holding into the end cannot creep the outer axle off the rail.
        loco.v.getAccessPanel().setSpeed(speed);
        loco.splineTick();
        assertPositions(loco, track, start);
        double away = speed > 0 ? -0.1 : 0.1;
        loco.v.getAccessPanel().setSpeed(away);
        loco.splineTick();
        assertPositions(loco, track, start + away);
    }

    @Test
    void overhangingWheelsCanDriveBackOntoTrack() {
        TrackSpline track = straightTrack(false);
        TrainHandler loco = wheeledConsist(track, 21);
        loco.v.getAccessPanel().setSpeed(-0.1);
        loco.splineTick();
        assertPositions(loco, track, 21);
        loco.v.getAccessPanel().setSpeed(3);
        loco.splineTick();
        assertPositions(loco, track, 24);
    }

    @Test
    void supportUsesScaledWheelBonesOnAnUncoupledCar() {
        TrackSpline track = straightTrack(false);
        TrainHandler car = car(List.of("axle_front", "axle_back"));
        when(car.v.getModel().getScale()).thenReturn(new Vector3f(2));
        car.setSplineId(track.getId());
        car.setS(94.3);
        car.placeLoadedCars();
        car.v.getAccessPanel().setSpeed(0.1);
        car.splineTick();
        assertEquals(94.3, car.getS(), 1e-8);
    }

    @Test
    void wheelSupportWrapsAcrossLoopSeam() {
        TrackSpline track = straightTrack(true);
        TrainHandler loco = wheeledConsist(track, 0.1);
        loco.v.getAccessPanel().setSpeed(-0.2);
        loco.splineTick();
        assertPositions(loco, track, track.length() - 0.1);
    }

    @Test
    void outerAxleCannotRunOffBranchTipOntoImaginaryStem() {
        TrackSpline stem = straightTrack(false);
        TrackSpline branch = TrackSpline.fromPoints(UUID.randomUUID(), "world", false,
                List.of(new double[]{0, 64, 50}, new double[]{100, 64, 50}));
        store.save(branch);
        TrackJunction junction = new TrackJunction(UUID.randomUUID(), stem.getId(), 50,
                1, TrackJunction.Side.LEFT, branch.getId(), true);
        store.saveJunction("world", junction);
        registry.loadFromDisk();
        TrainHandler loco = wheeledConsist(branch, 97.1);
        loco.applyConsist(new ConsistData(null, null, branch.getId().toString(), 97.1,
                1, junction.id.toString(), true));
        loco.placeLoadedCars();
        loco.v.getAccessPanel().setSpeed(0.2);
        loco.splineTick();
        assertPositions(loco, branch, 97.1);
    }

    @Test
    void wheelsCanEnterSelectedBranchBeforeCentreReachesStemEnd() {
        TrackSpline stem = straightTrack(false);
        TrackSpline branch = TrackSpline.fromPoints(UUID.randomUUID(), "world", false,
                List.of(new double[]{0, 64, 100}, new double[]{0, 64, 200}));
        store.save(branch);
        store.saveJunction("world", new TrackJunction(UUID.randomUUID(), stem.getId(), 100,
                1, TrackJunction.Side.LEFT, branch.getId(), true));
        registry.loadFromDisk();
        TrainHandler loco = wheeledConsist(stem, 97.1);
        loco.v.getAccessPanel().setSpeed(0.2);
        loco.splineTick();
        assertPositions(loco, stem, 97.3);
    }

    @Test
    void wheelsCrossBranchStartWithoutStopping() {
        TrackSpline stem = straightTrack(false);
        TrackSpline branch = TrackSpline.fromPoints(UUID.randomUUID(), "world", false,
                List.of(new double[]{0, 64, 50}, new double[]{100, 64, 50}));
        store.save(branch);
        TrackJunction junction = new TrackJunction(UUID.randomUUID(), stem.getId(), 50,
                1, TrackJunction.Side.LEFT, branch.getId(), true);
        store.saveJunction("world", junction);
        registry.loadFromDisk();
        TrainHandler loco = wheeledConsist(branch, 22.9);
        loco.applyConsist(new ConsistData(null, null, branch.getId().toString(), 22.9,
                1, junction.id.toString(), true));
        loco.v.getThrottle().setThrottle(-100);
        loco.v.getAccessPanel().setSpeed(-0.2);
        loco.splineTick();
        assertEquals(22.7, loco.getS(), 1e-8);
        assertEquals(-100, loco.v.getThrottle().getCurrent());
    }

    private TrainHandler wheeledConsist(TrackSpline track, double s) {
        TrainHandler loco = car(List.of("axle_front", "axle_back"));
        TrainHandler first = car(List.of("axle_front", "axle_back"));
        TrainHandler last = car(List.of("axle_front", "axle_back"));
        loco.setChild(first.v);
        first.setChild(last.v);
        loco.setSplineId(track.getId());
        loco.setS(s);
        loco.placeLoadedCars();
        return loco;
    }

    @Test
    void wallStopKeepsThrottleSetting() {
        TrackSpline track = straightTrack(false);
        TrainHandler loco = consist(track, 60.5);
        wall(loco, new BoundingBox(-1, 64, 65.5, 1, 68, 66.5));
        loco.v.getThrottle().setThrottle(100);
        loco.v.getAccessPanel().setSpeed(0.1);
        loco.splineTick();
        assertPositions(loco, track, 60.5);
        assertEquals(100, loco.v.getThrottle().getCurrent());
    }

    @Test
    void loopSeamKeepsThrottleSetting() {
        TrackSpline track = straightTrack(true);
        TrainHandler loco = consist(track, 0.1);
        loco.v.getThrottle().setThrottle(-100);
        loco.v.getAccessPanel().setSpeed(-0.2);
        loco.splineTick();
        assertPositions(loco, track, track.length() - 0.1);
        assertEquals(-100, loco.v.getThrottle().getCurrent());
    }

    @Test
    void supportingFloorDoesNotBlockMovement() {
        TrackSpline track = straightTrack(false);
        TrainHandler loco = consist(track, 60.5);
        wall(loco, new BoundingBox(-1, 63, 0, 1, 64.5, 100));
        loco.v.getAccessPanel().setSpeed(-1);
        loco.splineTick();
        assertPositions(loco, track, 59.5);
    }

    @Test
    void clearFastMovementCommitsAllCarsAtFullDistance() {
        TrackSpline track = straightTrack(false);
        TrainHandler loco = consist(track, 60.5);
        wall(loco, new BoundingBox(10, 64, 0, 11, 68, 100));
        loco.v.getAccessPanel().setSpeed(-8);
        loco.splineTick();
        assertPositions(loco, track, 52.5);
        loco.v.getAccessPanel().setSpeed(8);
        loco.splineTick();
        assertPositions(loco, track, 60.5);
    }

    @Test
    void wallAlongBendCannotBeSkippedByLongTick() {
        TrackSpline track = TrackSpline.fromPoints(UUID.randomUUID(), "world", false,
                List.of(new double[]{0, 64, 0}, new double[]{0, 64, 40},
                        new double[]{40, 64, 40}, new double[]{40, 64, 100}));
        store.save(track);
        registry.loadFromDisk();
        TrainHandler loco = consist(track, 80);
        wall(loco, new BoundingBox(10, 64, 20, 11, 68, 60));
        loco.v.getAccessPanel().setSpeed(-30);
        loco.splineTick();
        assertTrue(loco.getS() > 50);
        assertTrue(loco.getS() < 80);
        for (TrainHandler car = loco; car != null; car = car.hasChild() ? car.getChild().getTrainHandler() : null) {
            assertTrue(car.v.getEntity().getBoundingBox().getMinX() >= 11 - 1e-8);
        }
        assertPositions(loco, track, loco.getS());
    }

    @Test
    void blockedJunctionEntryDoesNotCommitRouteOrTeleportAnyCar() {
        TrackSpline stem = straightTrack(false);
        TrackSpline branch = TrackSpline.fromPoints(UUID.randomUUID(), "world", false,
                List.of(new double[]{0, 64, 50}, new double[]{100, 64, 50}));
        store.save(branch);
        TrackJunction junction = new TrackJunction(UUID.randomUUID(), stem.getId(), 50,
                1, TrackJunction.Side.LEFT, branch.getId(), true);
        store.saveJunction("world", junction);
        registry.loadFromDisk();
        TrainHandler loco = consist(stem, 49.9);
        // Entering the branch puts the locomotive at its start, front coupler at x 5.
        wall(loco, new BoundingBox(4.9, 64, 49, 5.2, 68, 51));
        loco.v.getAccessPanel().setSpeed(0.2);
        loco.splineTick();
        assertPositions(loco, stem, 49.9);
        assertNull(loco.toConsistData().getJunctionId());

        wall(loco, new BoundingBox(200, 64, 200, 201, 68, 201));
        loco.splineTick();
        assertEquals(branch.getId(), loco.getSplineId());
        assertEquals(junction.id.toString(), loco.toConsistData().getJunctionId());
        assertEquals(40.1, loco.getChild().getTrainHandler().getS(), 1e-8);
    }

    @Test
    void blockedTailOnStemStopsLocomotiveOnBranch() {
        TrackSpline stem = straightTrack(false);
        TrackSpline branch = TrackSpline.fromPoints(UUID.randomUUID(), "world", false,
                List.of(new double[]{0, 64, 50}, new double[]{100, 64, 50}));
        store.save(branch);
        TrackJunction junction = new TrackJunction(UUID.randomUUID(), stem.getId(), 50,
                1, TrackJunction.Side.LEFT, branch.getId(), true);
        store.saveJunction("world", junction);
        registry.loadFromDisk();
        TrainHandler loco = consist(branch, 3);
        loco.applyConsist(new ConsistData(null, null, branch.getId().toString(), 3d,
                1, junction.id.toString(), true));
        loco.placeLoadedCars();
        TrainHandler tail = loco.getChild().getTrainHandler().getChild().getTrainHandler();
        Location tailBefore = tail.v.getEntity().getLocation();
        // The tail sits at s 33 on the stem, so its back coupler is at z 28.
        wall(loco, new BoundingBox(-1, 64, 27, 1, 68, 28));
        loco.v.getAccessPanel().setSpeed(-0.1);
        loco.splineTick();
        assertEquals(branch.getId(), loco.getSplineId());
        assertEquals(3, loco.getS(), 1e-8);
        TrainHandler first = loco.getChild().getTrainHandler();
        TrainHandler last = first.getChild().getTrainHandler();
        assertEquals(stem.getId(), first.getSplineId());
        assertEquals(stem.getId(), last.getSplineId());
        assertEquals(43, first.getS(), 1e-8);
        assertEquals(33, last.getS(), 1e-8);
        assertEquals(3, loco.v.getEntity().getLocation().getX(), 1e-8);
        assertEquals(tailBefore, last.v.getEntity().getLocation());
    }

    @Test
    void carriageCrossingBranchStartDoesNotResetThrottle() {
        TrackSpline stem = straightTrack(false);
        TrackSpline branch = TrackSpline.fromPoints(UUID.randomUUID(), "world", false,
                List.of(new double[]{0, 64, 50}, new double[]{100, 64, 50}));
        store.save(branch);
        TrackJunction junction = new TrackJunction(UUID.randomUUID(), stem.getId(), 50,
                1, TrackJunction.Side.LEFT, branch.getId(), true);
        store.saveJunction("world", junction);
        registry.loadFromDisk();
        TrainHandler loco = consist(branch, 10.1);
        loco.applyConsist(new ConsistData(null, null, branch.getId().toString(), 10.1,
                1, junction.id.toString(), true));
        loco.placeLoadedCars();
        loco.v.getThrottle().setThrottle(-100);
        loco.v.getAccessPanel().setSpeed(-0.1);
        loco.splineTick();
        assertEquals(stem.getId(), loco.getChild().getTrainHandler().getSplineId());
        assertEquals(50, loco.getChild().getTrainHandler().getS(), 1e-8);
        assertEquals(-100, loco.v.getThrottle().getCurrent());
        loco.splineTick();
        assertEquals(stem.getId(), loco.getChild().getTrainHandler().getSplineId());
        assertEquals(-100, loco.v.getThrottle().getCurrent());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, -1})
    void reversalWhileCarsSpanJunctionKeepsRouteAndSpacing(int facing) {
        TrackSpline stem = straightTrack(false);
        TrackSpline branch = TrackSpline.fromPoints(UUID.randomUUID(), "world", false,
                List.of(new double[]{0, 64, 50}, new double[]{100, 64, 50}));
        store.save(branch);
        TrackJunction junction = new TrackJunction(UUID.randomUUID(), stem.getId(), 50,
                facing, TrackJunction.Side.LEFT, branch.getId(), true);
        store.saveJunction("world", junction);
        registry.loadFromDisk();
        TrainHandler loco = consist(branch, 3);
        loco.applyConsist(new ConsistData(null, null, branch.getId().toString(), 3d,
                1, junction.id.toString(), true));
        double expectedS = 3;
        for (double speed : new double[]{0.2, 0, -0.01, 0}) {
            expectedS += speed;
            loco.v.getAccessPanel().setSpeed(speed);
            loco.splineTick();
            assertEquals(branch.getId(), loco.getSplineId());
            assertEquals(expectedS, loco.getS(), 1e-8);
            TrainHandler car = loco;
            for (int i = 1; i <= 2; i++) {
                car = car.getChild().getTrainHandler();
                assertEquals(stem.getId(), car.getSplineId());
                assertEquals(50 - facing * (i * 10 - expectedS), car.getS(), 1e-8);
            }
        }
    }

    @Test
    void splittingTrackBehindTrainKeepsItWhereItWas() {
        TrackSpline track = denseTrack();
        TrainHandler loco = consist(track, 60);
        registry.digAt(track, 20);
        loco.splineTick();
        assertNotEquals(track.getId(), loco.getSplineId(), "Train must move onto the far piece");
        assertPositions(loco, registry.get(loco.getSplineId()).orElseThrow(), 39);
        assertEquals(60, loco.v.getEntity().getLocation().getZ(), 1e-8);
        assertEquals(40, loco.getChild().getTrainHandler().getChild().getTrainHandler()
                .v.getEntity().getLocation().getZ(), 1e-8);
    }

    @Test
    void trainOnSplitTrackCanDriveOn() {
        TrackSpline track = denseTrack();
        TrainHandler loco = consist(track, 60);
        registry.digAt(track, 20);
        loco.v.getAccessPanel().setSpeed(0.2);
        loco.splineTick();
        assertEquals(60.2, loco.v.getEntity().getLocation().getZ(), 1e-8);
        assertEquals(40.2, loco.getChild().getTrainHandler().getChild().getTrainHandler()
                .v.getEntity().getLocation().getZ(), 1e-8);
    }

    @Test
    void trimmingTrackStartDoesNotShiftTrain() {
        TrackSpline track = denseTrack();
        TrainHandler loco = consist(track, 60);
        registry.digAt(track, 0);
        loco.splineTick();
        assertEquals(track.getId(), loco.getSplineId());
        assertPositions(loco, registry.get(track.getId()).orElseThrow(), 59);
        assertEquals(60, loco.v.getEntity().getLocation().getZ(), 1e-8);
    }

    @Test
    void savingParkedTrainAfterSplitStoresItsRealPosition() {
        TrackSpline track = denseTrack();
        TrainHandler loco = consist(track, 60);
        registry.digAt(track, 20);
        ConsistData saved = loco.toConsistData();
        assertNotEquals(track.getId().toString(), saved.getSplineId());
        assertEquals(39, saved.getS(), 1e-8);
    }

    @Test
    void removingTrackUnderParkedTrainUnbindsIt() {
        TrackSpline track = denseTrack();
        TrainHandler loco = consist(track, 60);
        registry.delete(track.getId());
        loco.splineTick();
        assertFalse(loco.isBound());
    }

    @Test
    void splittingTrackAtCrossingKeepsTrainOnItsOwnLine() {
        TrackSpline track = denseTrack();
        TrackSpline crossing = crossingAt(60);
        TrainHandler loco = consist(registry.get(track.getId()).orElseThrow(), 60);
        registry.digAt(registry.get(track.getId()).orElseThrow(), 20);
        assertNotEquals(crossing.getId(), loco.getSplineId());
        assertNotEquals(track.getId(), loco.getSplineId());
        assertEquals(39, loco.getS(), 1e-8);
    }

    @Test
    void splittingStemKeepsRouteOfTrainLeavingBranch() {
        TrackSpline stem = denseTrack();
        TrackSpline branch = TrackSpline.fromPoints(UUID.randomUUID(), "world", false,
                List.of(new double[]{0, 64, 50}, new double[]{100, 64, 50}));
        store.save(branch);
        TrackJunction junction = new TrackJunction(UUID.randomUUID(), stem.getId(), 50,
                -1, TrackJunction.Side.LEFT, branch.getId(), true);
        store.saveJunction("world", junction);
        registry.loadFromDisk();
        TrainHandler loco = consist(registry.get(stem.getId()).orElseThrow(), 55);
        loco.applyConsist(new ConsistData(null, null, stem.getId().toString(), 55d,
                1, junction.id.toString(), true));
        loco.placeLoadedCars();
        TrainHandler first = loco.getChild().getTrainHandler();
        assertEquals(branch.getId(), first.getSplineId(), "Setup: first car trails onto the branch");
        Location before = first.v.getEntity().getLocation();

        registry.digAt(registry.get(stem.getId()).orElseThrow(), 10);
        loco.splineTick();

        assertNotEquals(stem.getId(), loco.getSplineId());
        assertEquals(junction.id.toString(), loco.toConsistData().getJunctionId());
        assertEquals(branch.getId(), first.getSplineId());
        assertEquals(before.getX(), first.v.getEntity().getLocation().getX(), 1e-8);
        assertEquals(before.getZ(), first.v.getEntity().getLocation().getZ(), 1e-8);
    }

    @Test
    void joiningAnotherTrackToTrainTracksStartKeepsTrainFacingTheSameWay() throws Exception {
        TrackSpline track = denseTrack();
        TrainHandler loco = consist(track, 60);
        TrainHandler first = loco.getChild().getTrainHandler();
        TrainHandler last = first.getChild().getTrainHandler();
        registry.occupiedBy(id -> List.of(loco, first, last).stream()
                .anyMatch(car -> id.equals(car.getSplineId())));
        registry.lay("world", 0, 64, -20, 0, 64, -30);
        // Start to start: one of the two tracks has to be reversed.
        registry.lay("world", 0, 64, 0, 0, 64, -20);
        loco.splineTick();
        assertEquals(60, loco.v.getEntity().getLocation().getZ(), 1e-6);
        assertEquals(50, first.v.getEntity().getLocation().getZ(), 1e-6);
        assertEquals(40, last.v.getEntity().getLocation().getZ(), 1e-6);
        loco.v.getAccessPanel().setSpeed(0.2);
        loco.splineTick();
        assertEquals(60.2, loco.v.getEntity().getLocation().getZ(), 1e-6);
    }

    @Test
    void loadingTrainAfterTrackWasSplitWhileUnloadedKeepsItsPosition() {
        TrackSpline track = denseTrack();
        TrainHandler loco = consist(track, 60);
        inWorld(loco, "world");
        savedBoneYaw(loco, 0);
        registry.onRebuilt(null);
        registry.digAt(track, 20);
        // Loading restores the (spline, s) saved before the edit.
        loco.applyConsist(new ConsistData(null, null, track.getId().toString(), 60d, 1));
        loco.placeLoadedCars();
        assertNotEquals(track.getId(), loco.getSplineId());
        assertEquals(39, loco.getS(), 1e-8);
        assertEquals(60, loco.v.getEntity().getLocation().getZ(), 1e-8);
        assertEquals(40, loco.getChild().getTrainHandler().getChild().getTrainHandler()
                .v.getEntity().getLocation().getZ(), 1e-8);
    }

    @Test
    void loadingTrainOntoTrackReversedWhileUnloadedUnbindsIt() {
        TrackSpline track = denseTrack();
        TrainHandler loco = consist(track, 60);
        inWorld(loco, "world");
        // Entity yaw 0 and bone yaw 0: the model faces +z, the track's +s.
        savedBoneYaw(loco, 0);
        registry.onRebuilt(null);
        List<double[]> reversed = new ArrayList<>(track.xyz());
        Collections.reverse(reversed);
        registry.replace(TrackSpline.fromPoints(track.getId(), "world", false, reversed));
        loco.applyConsist(new ConsistData(null, null, track.getId().toString(), 60d, 1));
        loco.placeLoadedCars();
        assertFalse(loco.isBound(), "A train must not come back facing the other way");
    }

    @Test
    void loadingTrainAtMiddleOfTrackReversedWhileUnloadedUnbindsIt() {
        TrackSpline track = denseTrack();
        TrainHandler loco = consist(track, 50);
        inWorld(loco, "world");
        savedBoneYaw(loco, 0);
        registry.onRebuilt(null);
        List<double[]> reversed = new ArrayList<>(track.xyz());
        Collections.reverse(reversed);
        // s=50 still lands on the same spot, but the track now runs the other way.
        registry.replace(TrackSpline.fromPoints(track.getId(), "world", false, reversed));
        loco.applyConsist(new ConsistData(null, null, track.getId().toString(), 50d, 1));
        loco.placeLoadedCars();
        assertFalse(loco.isBound());
    }

    @ParameterizedTest
    @CsvSource({"0, true", "45, true", "-45, true", "90, false", "180, false", "-120, false"})
    void modelMustFaceAlongTrackToRebind(float modelYaw, boolean along) {
        assertEquals(along, TrainHandler.facesAlong(modelYaw, new TrackPose(0, 64, 0, 0f, 0f)));
    }

    @Test
    void loadingTrainWhoseTrackWasRemovedWhileUnloadedUnbindsIt() {
        TrackSpline track = denseTrack();
        TrainHandler loco = consist(track, 60);
        inWorld(loco, "world");
        registry.onRebuilt(null);
        registry.delete(track.getId());
        loco.applyConsist(new ConsistData(null, null, track.getId().toString(), 60d, 1));
        loco.placeLoadedCars();
        assertFalse(loco.isBound());
    }

    @Test
    void savedTrainOnTrackCountsAsOccupyingItWhileUnloaded() throws Exception {
        TrackSpline track = denseTrack();
        Field repositoryField = VehicleFramework.class.getDeclaredField("vehicleRepository");
        repositoryField.setAccessible(true);
        Object previous = repositoryField.get(null);
        VehicleRepository repository = VehicleRepository.open(directory.resolve("vehicles.db").toFile());
        try {
            repositoryField.set(null, repository);
            assertFalse(TrainHandler.anyTrainOn(track.getId()));
            String payload = "{\"splineId\":\"" + track.getId() + "\",\"s\":60.0,\"travelSign\":1}";
            repository.upsert(new VehicleSnapshot(UUID.randomUUID().toString(), "loco", "world",
                    0, 64.5, 60, 0f, 0, 3, payload, VehicleRepository.SCHEMA_VERSION, 1, false, 1L));
            assertTrue(TrainHandler.anyTrainOn(track.getId()));
            assertFalse(TrainHandler.anyTrainOn(UUID.randomUUID()));
        } finally {
            repositoryField.set(null, previous);
            repository.close();
        }
    }

    @Test
    void deletingTrackDoesNotMoveTrainOntoCrossingTrack() {
        TrackSpline track = denseTrack();
        crossingAt(60);
        TrainHandler loco = consist(registry.get(track.getId()).orElseThrow(), 60);
        registry.delete(track.getId());
        loco.splineTick();
        assertFalse(loco.isBound());
    }

    @Test
    void breakingTrackPieceElsewhereKeepsTrainPosition() {
        TrackSpline track = denseTrack();
        TrainHandler loco = consist(track, 60);
        registry.replace(track.withSegment(80, track.segment(80).withBroken(true)));
        loco.splineTick();
        assertPositions(loco, track, 60);
    }

    @Test
    void collisionPathRunsFromTheTrailingCouplerPastTheLeadingOne() {
        TrackSpline track = denseTrack();
        TrainHandler loco = consist(track, 60);
        TrainPath forward = loco.collisionPath(10);
        assertEquals(30, forward.trainLength(), 1e-6);
        assertEquals(40, forward.length(), 1e-6);
        assertEquals(List.of(new TrainPath.Range(track.getId(), 35, 65, 1)), rounded(forward.ranges(0, 30)));

        loco.v.getAccessPanel().setSpeed(-0.72);
        TrainPath backward = loco.collisionPath(-5);
        assertEquals(30, backward.length(), 1e-6);
        assertEquals(List.of(new TrainPath.Range(track.getId(), 35, 65, -1)), rounded(backward.ranges(0, 30)));

        TrainHandler car = loco.getChild().getTrainHandler();
        when(car.v.hasParent()).thenReturn(true);
        assertNull(car.collisionPath(10));
        assertNull(car().collisionPath(10));
    }

    /**
     * Two three-car trains run at each other on a straight line, moved by the real track
     * code tick by tick. The warning must come at least 30 seconds before the real
     * TrainCollision check explodes them, and never promise more time than is left.
     */
    @ParameterizedTest
    @CsvSource({
            "100, 100, 0, 1100, -1",   // both at full throttle
            "100, 0, 0, 600, -1",      // running at a stopped train
            "100, 0, 1, 1100, -1",     // the other driver pulls away towards us, opening the throttle each tick
            "50, 0, 0, 800, 403"})     // cruising at half throttle, then opening it up just after a check
    void headOnTrainsAreWarnedBeforeTheyCollide(int throttleA, int throttleB, int rampB, double startB,
            int rampAFrom) throws Exception {
        TrackSpline track = TrackSpline.fromPoints(UUID.randomUUID(), "world", false,
                List.of(new double[]{0, 64, 0}, new double[]{0, 64, 1400}));
        store.save(track);
        registry.loadFromDisk();
        TrainHandler a = consist(track, 100);
        TrainHandler b = consist(track, startB);
        b.applyConsist(new ConsistData(null, null, track.getId().toString(), startB, 1, null, null, -1, Map.of()));
        b.placeLoadedCars();
        World world = openWorld();
        Player driverA = mock(Player.class);
        Player driverB = mock(Player.class);
        when(driverA.getUniqueId()).thenReturn(UUID.randomUUID());
        when(driverB.getUniqueId()).thenReturn(UUID.randomUUID());
        List<ActiveVehicle> vehicles = new ArrayList<>();
        int[] exploded = {-1};
        int[] tick = {0};
        for (TrainHandler loco : List.of(a, b)) {
            ActiveVehicle parent = null;
            for (TrainHandler car : cars(loco)) {
                ActiveVehicle v = car.v;
                when(v.isTrain()).thenReturn(true);
                when(v.getEntity().getWorld()).thenReturn(world);
                when(v.hasDeathData(VehicleDeath.EXPLODE)).thenReturn(true);
                doAnswer(call -> {
                    if (exploded[0] < 0) exploded[0] = tick[0];
                    return null;
                }).when(v).kill(VehicleDeath.EXPLODE);
                SeatHandler seats = mock(SeatHandler.class);
                when(seats.getPassengers()).thenReturn(parent == null
                        ? new ArrayList<>(List.of(loco == a ? driverA : driverB)) : new ArrayList<>());
                when(v.getSeatHandler()).thenReturn(seats);
                if (parent != null) {
                    when(v.hasParent()).thenReturn(true);
                    when(v.getParent()).thenReturn(parent);
                }
                parent = v;
                vehicles.add(v);
            }
        }
        // No saved cars: only these two trains are on the line.
        Field repositoryField = VehicleFramework.class.getDeclaredField("vehicleRepository");
        repositoryField.setAccessible(true);
        Object previousRepository = repositoryField.get(null);
        repositoryField.set(null, null);
        TrainCollisionWarning.clear();
        double saved = Cache.trainCollisionWarningSeconds;
        Cache.trainCollisionWarningSeconds = 30;
        Cache.trainCollisionWarningMargin = 2;
        Cache.trainCollisionWarningCheckTicks = 10;
        // Locomotives overdrive to 120.
        for (TrainHandler loco : List.of(a, b)) {
            when(loco.v.getThrottle()).thenReturn(new Throttle("Throttle", 120, -100, null));
        }
        a.v.getThrottle().setThrottle(throttleA);
        b.v.getThrottle().setThrottle(throttleB);
        List<double[]> forecasts = new ArrayList<>();
        try {
            for (; tick[0] < 2400 && exploded[0] < 0; tick[0]++) {
                if (rampB > 0 && b.v.getThrottle().getCurrent() < 100) {
                    b.v.getThrottle().setThrottle(b.v.getThrottle().getCurrent() + rampB);
                }
                if (rampAFrom >= 0 && tick[0] >= rampAFrom && a.v.getThrottle().getCurrent() < 120) {
                    a.v.getThrottle().setThrottle(a.v.getThrottle().getCurrent() + 1);
                }
                // Locomotive speed is 0.72 blocks a tick at full throttle, as the engine sets it.
                a.v.getAccessPanel().setSpeed(0.72 * a.v.getThrottle().getCurrent() / 100);
                b.v.getAccessPanel().setSpeed(0.72 * b.v.getThrottle().getCurrent() / 100);
                if (tick[0] % 10 == 0) {
                    TrainCollisionWarning.Warning warning = TrainCollisionWarning.check(vehicles).get(driverA);
                    forecasts.add(new double[]{tick[0], warning == null ? -1 : warning.seconds()});
                }
                a.splineTick();
                b.splineTick();
                TrainCollision.tick(vehicles);
            }
        } finally {
            Cache.trainCollisionWarningSeconds = saved;
            TrainCollisionWarning.clear();
            repositoryField.set(null, previousRepository);
        }
        assertTrue(exploded[0] > 0, "The trains never met");
        double firstLead = -1;
        StringBuilder log = new StringBuilder(String.format("throttle A=%d B=%d ramp=%d from=%d: collision after %.2f s%n",
                throttleA, throttleB, rampB, rampAFrom, exploded[0] / 20.0));
        for (double[] forecast : forecasts) {
            if (forecast[1] < 0) continue;
            double left = (exploded[0] - forecast[0]) / 20.0;
            if (firstLead < 0) firstLead = left;
            assertTrue(forecast[1] <= left + 1e-9, "Promised " + forecast[1] + " s with " + left + " s left");
            // Early only by the margin and what the other driver might still do.
            assertTrue(forecast[1] >= left - 3.5, "Warned of " + forecast[1] + " s with " + left + " s left");
            if ((int) forecast[0] % 100 == 0 || firstLead == left) {
                log.append(String.format("  t=%5.1f s  warning %5.1f s  actual %5.2f s%n",
                        forecast[0] / 20.0, forecast[1], left));
            }
        }
        System.out.print(log);
        assertTrue(firstLead >= 30, "First warning only " + firstLead + " s before the collision");
    }

    // A world with nothing in the way, so only the trains can meet.
    private static World openWorld() {
        World world = stub(World.class);
        Block air = stub(Block.class);
        when(air.isPassable()).thenReturn(true);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(air);
        return world;
    }

    private static List<TrainPath.Range> rounded(List<TrainPath.Range> ranges) {
        return ranges.stream().map(r -> new TrainPath.Range(r.splineId(),
                Math.round(r.lo() * 1e6) / 1e6, Math.round(r.hi() * 1e6) / 1e6, r.direction())).toList();
    }

    @ParameterizedTest
    @CsvSource({"45, 1, true", "66, 1, true", "34, 1, true", "33.5, 1, false",
            "67.5, 1, false", "20, 1, false"})
    void consistOccupiesTrackOutToItsCouplers(double at, double halfSpan, boolean occupied) {
        TrackSpline track = denseTrack();
        TrainHandler loco = consist(track, 60);
        assertEquals(occupied, loco.occupies(List.of(new TrackRegistry.Span(track.getId(), at, halfSpan))));
    }

    @Test
    void digTargetCoversTheEdgesItRemoves() {
        TrackSpline track = denseTrack();
        TrackRegistry.DigTarget target = registry.digTarget("world", 0, 64, 20.2).orElseThrow();
        assertEquals(20, target.index());
        assertEquals(1, target.spans().size());
        assertEquals(20, target.spans().get(0).centreS(), 1e-8);
        assertEquals(1, target.spans().get(0).halfSpan(), 1e-8);
        assertEquals(track.getId(), target.spline().getId());
    }

    @ParameterizedTest
    @CsvSource({"1, false", "-1, false", "1, true", "-1, true"})
    void wholeTrainTraversesEitherFacingTurnoutWithoutJumping(int facing, boolean reverse) {
        TrackSpline stem = straightTrack(false);
        TrackJunction junction = smoothTurnout(stem, 50, facing, false);
        int orientation = reverse ? -facing : facing;
        double start = 50 - facing * (reverse ? 38 : 8);
        TrainHandler loco = wheeledConsist(stem, start);
        loco.getChild().getTrainHandler().getChild().getTrainHandler().setChild(car(List.of("axle_front", "axle_back")).v);
        loco.applyConsist(new ConsistData(null, null, stem.getId().toString(), start,
                facing, null, null, orientation, java.util.Map.of()));
        loco.placeLoadedCars();
        registry.onJunctionOccupied(loco::holdsJunction);
        List<float[]> rotations = captureRotations(loco);
        double speed = reverse ? -0.25 : 0.25;
        loco.v.getAccessPanel().setSpeed(speed);
        // The current speed determines approach direction even when the throttle is braking.
        loco.v.getThrottle().setThrottle(reverse ? 100 : -100);
        loco.holdJunction(TrackJunction.Side.LEFT);
        assertTrue(registry.getJunction(junction.id).orElseThrow().thrown);
        int ticks = reverse ? 232 : 272;
        boolean sawLocked = false;
        for (int tick = 0; tick < ticks; tick++) {
            List<Location> before = carLocations(loco);
            loco.splineTick();
            assertContinuousCoupled(loco, rotations, before, 1.1);
            if (loco.holdsJunction(junction.id)) {
                sawLocked = true;
                assertFalse(registry.setThrown(junction.id, false), "An occupied switch must not move");
            }
        }
        assertTrue(sawLocked);
        assertFalse(loco.holdsJunction(junction.id), "Release the points after the whole train clears");
        int i = 0;
        for (TrainHandler car : cars(loco)) {
            assertEquals(junction.branchSplineId, car.getSplineId());
            assertEquals(reverse ? -1 : 1, car.getOrientation());
            assertEquals(reverse ? 20 + i * 10 : 60 - i * 10, car.getS(), 1e-6);
            i++;
        }
        assertTrue(registry.setThrown(junction.id, false));
        // Leaving the branch is a trailing move: return along the same connected rails
        // even if another train has since set the turnout through.
        for (int tick = 0; tick < ticks; tick++) {
            List<Location> before = carLocations(loco);
            loco.v.getAccessPanel().setSpeed(-speed);
            loco.splineTick();
            assertContinuousCoupled(loco, rotations, before, 1.1);
        }
        assertEquals(stem.getId(), loco.getSplineId());
        assertEquals(start, loco.getS(), 1e-6);
        assertEquals(orientation, loco.getOrientation());
        assertFalse(loco.holdsJunction(junction.id));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, -1})
    void reverseMidTurnoutAndReloadRetracesTheChosenRoute(int facing) {
        TrackSpline stem = straightTrack(false);
        TrackJunction junction = smoothTurnout(stem, 50, facing, true);
        double start = 50 - facing * 28;
        TrainHandler loco = wheeledConsist(stem, start);
        loco.applyConsist(new ConsistData(null, null, stem.getId().toString(), start,
                facing, null, null, -facing, java.util.Map.of()));
        loco.placeLoadedCars();
        List<float[]> rotations = captureRotations(loco);
        registry.onJunctionOccupied(loco::holdsJunction);
        List<Location> initial = carLocations(loco);
        for (int tick = 0; tick < 80; tick++) {
            List<Location> before = carLocations(loco);
            loco.v.getAccessPanel().setSpeed(-0.25);
            loco.splineTick();
            assertContinuousCoupled(loco, rotations, before, 1.1);
        }
        assertTrue(loco.holdsJunction(junction.id));
        assertEquals(stem.getId(), loco.getSplineId(), "Engine has not entered yet");
        assertEquals(junction.branchSplineId, cars(loco).get(2).getSplineId(), "Leading car already diverged");
        List<Location> saved = carLocations(loco);
        for (TrainHandler car : cars(loco)) {
            org.json.simple.JSONObject json = new org.json.simple.JSONObject();
            car.toConsistData().put(json);
            car.applyConsist(ConsistData.fromJson(json));
        }
        loco.v.getAccessPanel().setSpeed(0);
        loco.splineTick();
        for (int i = 0; i < saved.size(); i++) { assertEquals(saved.get(i), carLocations(loco).get(i)); }
        for (int tick = 0; tick < 80; tick++) {
            List<Location> before = carLocations(loco);
            loco.v.getAccessPanel().setSpeed(0.25);
            loco.splineTick();
            assertContinuousCoupled(loco, rotations, before, 1.1);
        }
        for (int i = 0; i < initial.size(); i++) {
            assertEquals(0, initial.get(i).toVector().distance(carLocations(loco).get(i).toVector()), 1e-6);
        }
        assertFalse(loco.holdsJunction(junction.id));
    }

    @Test
    void reversingTrainRetainsTwoAdjacentSwitchesUntilEachClears() {
        TrackSpline stem = straightTrack(false);
        TrackJunction first = smoothTurnout(stem, 70, -1, false);
        TrackJunction second = smoothTurnout(stem, 55, -1, true);
        TrainHandler loco = wheeledConsist(stem, 98);
        registry.onJunctionOccupied(loco::holdsJunction);
        List<float[]> rotations = captureRotations(loco);
        boolean spannedBoth = false;
        for (int tick = 0; tick < 244; tick++) {
            List<Location> before = carLocations(loco);
            loco.v.getAccessPanel().setSpeed(-0.25);
            loco.splineTick();
            assertContinuousCoupled(loco, rotations, before, 1.1);
            spannedBoth |= loco.holdsJunction(first.id) && loco.holdsJunction(second.id);
        }
        assertTrue(spannedBoth);
        for (TrainHandler car : cars(loco)) { assertEquals(second.branchSplineId, car.getSplineId()); }
        assertFalse(loco.holdsJunction(first.id));
        assertFalse(loco.holdsJunction(second.id));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, -1})
    void oldOrOppositeFacingSavesStillRejectTrackReversedWhileUnloaded(int orientation) {
        TrackSpline track = straightTrack(false);
        TrainHandler loco = car();
        loco.applyConsist(new ConsistData(null, null, track.getId().toString(), 50d,
                -1, null, null, orientation, java.util.Map.of()));
        loco.placeLoadedCars();
        savedBoneYaw(loco, orientation < 0 ? 180 : 0);
        inWorld(loco, "world");
        loco.applyConsist(loco.toConsistData());
        loco.splineTick();
        assertTrue(loco.isBound(), "A saved -s orientation is valid on unchanged track");
        List<double[]> points = new ArrayList<>(track.xyz());
        Collections.reverse(points);
        registry.replace(TrackSpline.fromPoints(track.getId(), "world", false, points));
        loco.applyConsist(loco.toConsistData());
        loco.splineTick();
        assertFalse(loco.isBound(), "Reversing the underlying track still invalidates the saved pose");
    }

    @ParameterizedTest
    @CsvSource({"1, 2, -4", "-1, 4, -2"})
    void asymmetricWheelsDoNotFreezeThroughBeforeLeadingWheelArrives(int facing, float front, float back) {
        TrackSpline stem = straightTrack(false);
        TrackJunction junction = smoothTurnout(stem, 50, facing, true);
        TrainHandler loco = car(List.of("axle_front", "axle_back"));
        connector(loco.v, loco.v.getModel(), "axle_front", front);
        connector(loco.v, loco.v.getModel(), "axle_back", back);
        loco.setSplineId(stem.getId());
        loco.setS(50 - facing * 5);
        loco.placeLoadedCars();
        loco.v.getAccessPanel().setSpeed(facing * 0.25);
        for (int tick = 0; tick < 24; tick++) { loco.splineTick(); }
        assertEquals(junction.branchSplineId, loco.getSplineId());
        assertEquals(1, loco.getS(), 1e-6);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, -1})
    void routeSurvivesWhileUnloadedTailWaitsToRelink(int facing) {
        TrackSpline stem = straightTrack(false);
        TrackJunction junction = smoothTurnout(stem, 50, facing, true);
        double start = 50 - facing * 28;
        TrainHandler loco = wheeledConsist(stem, start);
        loco.applyConsist(new ConsistData(null, null, stem.getId().toString(), start,
                facing, null, null, -facing, java.util.Map.of()));
        loco.placeLoadedCars();
        loco.v.getAccessPanel().setSpeed(-0.25);
        for (int tick = 0; tick < 80; tick++) { loco.splineTick(); }
        TrainHandler child = loco.getChild().getTrainHandler();
        TrainHandler tail = child.getChild().getTrainHandler();
        assertEquals(junction.branchSplineId, tail.getSplineId());
        Location tailAt = tail.v.getEntity().getLocation();
        loco.setPendingChild(child.v.getUUID());
        loco.setChild(null);
        loco.v.getAccessPanel().setSpeed(0);
        loco.splineTick();
        assertTrue(loco.holdsJunction(junction.id), "The unloaded tail still occupies the chosen branch");
        org.json.simple.JSONObject saved = new org.json.simple.JSONObject();
        loco.toConsistData().put(saved);
        loco.applyConsist(ConsistData.fromJson(saved));
        loco.splineTick();
        assertTrue(loco.holdsJunction(junction.id));
        loco.setChild(child.v);
        loco.placeLoadedCars();
        assertEquals(tailAt, tail.v.getEntity().getLocation());
        for (int tick = 0; tick < 140; tick++) {
            loco.v.getAccessPanel().setSpeed(-0.25);
            loco.splineTick();
        }
        assertFalse(loco.holdsJunction(junction.id), "Resolved snapshots must eventually release the lock");
        assertTrue(tail.toConsistData().getJunctions().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, -1})
    void childFirstLoadKeepsTheRouteUntilItsParentReturns(int facing) {
        TrackSpline stem = straightTrack(false);
        TrackJunction junction = smoothTurnout(stem, 50, facing, true);
        double start = 50 - facing * 28;
        TrainHandler loco = wheeledConsist(stem, start);
        loco.applyConsist(new ConsistData(null, null, stem.getId().toString(), start,
                facing, null, null, -facing, java.util.Map.of()));
        loco.placeLoadedCars();
        loco.v.getAccessPanel().setSpeed(-0.25);
        for (int tick = 0; tick < 80; tick++) { loco.splineTick(); }
        TrainHandler tail = cars(loco).get(2);
        tail.setPendingParent(cars(loco).get(1).v.getUUID());
        org.json.simple.JSONObject saved = new org.json.simple.JSONObject();
        tail.toConsistData().put(saved);
        ConsistData restored = ConsistData.fromJson(saved);
        assertEquals(true, restored.getJunctions().get(junction.id.toString()));
        // A car well beyond the frog may load before its parent which still spans it.
        TrainHandler loaded = car();
        loaded.applyConsist(new ConsistData(restored.getParent(), null, junction.branchSplineId.toString(),
                30d, 1, null, null, -1, restored.getJunctions()));
        loaded.splineTick();
        assertTrue(loaded.holdsJunction(junction.id));
        loaded.setPendingParent(null);
        loaded.placeLoadedCars();
        assertFalse(loaded.holdsJunction(junction.id));
    }

    @Test
    void relinkRecoversCarriageSnapshotBeforePlacingTheTrain() {
        TrackSpline stem = straightTrack(false);
        TrackJunction junction = smoothTurnout(stem, 50, -1, false);
        TrainHandler loco = car();
        loco.applyConsist(new ConsistData(null, null, stem.getId().toString(), 58d, -1));
        TrainHandler child = car();
        child.applyConsist(new ConsistData(loco.v.getUUID(), null, junction.branchSplineId.toString(),
                2d, 1, null, null, -1, java.util.Map.of(junction.id.toString(), true)));
        child.splineTick();
        loco.setChild(child.v);
        child.setPendingParent(null);
        loco.placeLoadedCars();
        assertTrue(loco.holdsJunction(junction.id));
        assertEquals(junction.branchSplineId, child.getSplineId());
        assertEquals(2, child.getS(), 1e-6);
    }

    private TrackJunction smoothTurnout(TrackSpline stem, double at, int facing, boolean thrown) {
        List<double[]> points = new ArrayList<>();
        for (int i = 0; i <= 90; i++) {
            double a = Math.toRadians(i);
            points.add(new double[]{facing * 32 * (1 - Math.cos(a)), 64, at + facing * 32 * Math.sin(a)});
        }
        points.add(new double[]{facing * 132, 64, at + facing * 32});
        TrackSpline branch = TrackSpline.fromPoints(UUID.randomUUID(), "world", false, points);
        store.save(branch);
        TrackJunction junction = new TrackJunction(UUID.randomUUID(), stem.getId(), at, facing,
                TrackJunction.Side.LEFT, branch.getId(), thrown, 12);
        store.saveJunction("world", junction);
        registry.loadFromDisk();
        return junction;
    }

    private List<TrainHandler> cars(TrainHandler loco) {
        List<TrainHandler> cars = new ArrayList<>();
        for (TrainHandler car = loco; car != null; car = car.hasChild() ? car.getChild().getTrainHandler() : null) {
            cars.add(car);
        }
        return cars;
    }

    private List<Location> carLocations(TrainHandler loco) {
        return cars(loco).stream().map(car -> car.v.getEntity().getLocation()).toList();
    }

    private List<float[]> captureRotations(TrainHandler loco) {
        List<float[]> rotations = new ArrayList<>();
        for (TrainHandler car : cars(loco)) {
            float[] rotation = new float[2];
            rotations.add(rotation);
            BoneRotator rotator = stub(BoneRotator.class);
            doAnswer(call -> {
                rotation[0] = call.getArgument(0);
                rotation[1] = call.getArgument(1);
                return true;
            }).when(rotator).rotateToTarget(org.mockito.ArgumentMatchers.anyFloat(),
                    org.mockito.ArgumentMatchers.anyFloat(), org.mockito.ArgumentMatchers.anyFloat(),
                    org.mockito.ArgumentMatchers.anyFloat(), org.mockito.ArgumentMatchers.anyBoolean(),
                    org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean());
            BehaviourHandler behaviour = stub(BehaviourHandler.class);
            when(behaviour.getRotator()).thenReturn(rotator);
            when(car.v.getBehaviourHandler()).thenReturn(behaviour);
        }
        return rotations;
    }

    private void assertContinuousCoupled(TrainHandler loco, List<float[]> rotations, List<Location> before, double bound) {
        List<TrainHandler> cars = cars(loco);
        for (int i = 0; i < cars.size(); i++) {
            assertTrue(before.get(i).toVector().distance(cars.get(i).v.getEntity().getLocation().toVector()) < bound, "Car jumped at a turnout");
            if (i > 0) {
                assertEquals(0, modelAnchor(cars.get(i - 1), rotations.get(i - 1), -5)
                        .distance(modelAnchor(cars.get(i), rotations.get(i), 5)), 1e-5,
                        "car=" + i + " s=" + cars.get(i).getS() + " orientation=" + cars.get(i).getOrientation()
                        + " parent=" + cars.get(i - 1).getS() + " yaw=" + rotations.get(i)[0]
                        + " parentYaw=" + rotations.get(i - 1)[0] + " speed=" + loco.v.getAccessPanel().getSpeed());
            }
        }
    }

    private void assertSequence(TrainHandler loco, TrackSpline track, double[] expected) {
        double[] speeds = {0.2, 0, -0.01, 0, 0.2};
        for (int i = 0; i < speeds.length; i++) {
            loco.v.getAccessPanel().setSpeed(speeds[i]);
            loco.v.getAccessPanel().setReverse(speeds[i] < 0);
            loco.splineTick();
            assertPositions(loco, track, expected[i]);
        }
    }

    private void assertPositions(TrainHandler loco, TrackSpline track, double s) {
        TrainHandler car = loco;
        for (int i = 0; i < 3; i++) {
            double expected = s - i * 10;
            if (track.isLoop()) expected = (expected % track.length() + track.length()) % track.length();
            assertEquals(track.getId(), car.getSplineId());
            assertEquals(expected, car.getS(), 1e-8, "Car " + i + " changed its coupled position");
            // On curves a rigid carriage's centre shifts to close the coupling;
            // its route coordinate stays fixed. Straight consists still sit on the spline.
            if (i == 0 || track.getSamples().stream().allMatch(sample -> sample.yaw == track.first().yaw)) {
                assertEquals(track.sampleAt(expected).x, car.v.getEntity().getLocation().getX(), 1e-8);
                assertEquals(track.sampleAt(expected).z, car.v.getEntity().getLocation().getZ(), 1e-8);
            }
            if (i < 2) car = car.getChild().getTrainHandler();
        }
    }

    private TrackSpline denseTrack() {
        List<double[]> points = new ArrayList<>();
        for (int z = 0; z <= 100; z++) {
            points.add(new double[]{0, 64, z});
        }
        TrackSpline track = TrackSpline.fromPoints(UUID.randomUUID(), "world", false, points);
        store.save(track);
        registry.loadFromDisk();
        return registry.get(track.getId()).orElseThrow();
    }

    private TrackSpline crossingAt(double z) {
        TrackSpline crossing = TrackSpline.fromPoints(UUID.randomUUID(), "world", false,
                List.of(new double[]{-20, 64, z}, new double[]{20, 64, z}));
        store.save(crossing);
        registry.loadFromDisk();
        return crossing;
    }

    private TrackSpline straightTrack(boolean loop) {
        TrackSpline track = TrackSpline.fromPoints(UUID.randomUUID(), "world", loop,
                List.of(new double[]{0, 64, 0}, new double[]{0, 64, 100}));
        store.save(track);
        registry.loadFromDisk();
        return track;
    }

    private TrainHandler consist(TrackSpline track, double s) {
        TrainHandler loco = car();
        TrainHandler first = car();
        TrainHandler second = car();
        loco.setChild(first.v);
        first.setChild(second.v);
        registry.onRebuilt((old, rebuilt) -> List.of(loco, first, second)
                .forEach(car -> car.retrack(old, rebuilt)));
        loco.setSplineId(track.getId());
        loco.setS(s);
        loco.placeLoadedCars();
        return loco;
    }

    private TrainHandler car() {
        return car(List.of());
    }

    private TrainHandler car(List<String> wheels) {
        ActiveVehicle vehicle = stub(ActiveVehicle.class);
        Entity entity = stub(Entity.class);
        Location[] location = {new Location(null, 0, 64, 0)};
        when(entity.getLocation()).thenAnswer(call -> location[0].clone());
        when(entity.getBoundingBox()).thenAnswer(call -> new BoundingBox(
                location[0].getX() - 0.5, location[0].getY(), location[0].getZ() - 0.5,
                location[0].getX() + 0.5, location[0].getY() + 2, location[0].getZ() + 0.5));
        when(entity.teleport(any(Location.class))).thenAnswer(call -> {
            location[0] = call.<Location>getArgument(0).clone();
            return true;
        });
        when(vehicle.getEntity()).thenReturn(entity);
        when(vehicle.getUUID()).thenReturn(UUID.randomUUID().toString());
        when(vehicle.getAccessPanel()).thenReturn(new AccessPanel());
        when(vehicle.getThrottle()).thenReturn(new Throttle("Throttle", 100, -100, null));
        when(vehicle.getMoveControls()).thenReturn(stub(VehicleMovementController.class));
        ActiveModel model = stub(ActiveModel.class);
        when(model.getScale()).thenReturn(new Vector3f(1));
        when(vehicle.getModel()).thenReturn(model);
        Connector front = connector(vehicle, model, "front", 5);
        Connector back = connector(vehicle, model, "back", -5);
        connector(vehicle, model, "axle_front", 2.875f);
        connector(vehicle, model, "axle_back", -2.875f);
        YamlConfiguration config = new YamlConfiguration();
        config.set("wheel-bones", wheels);
        TrainHandler handler = new TrainHandler(config) {
            @Override public boolean isAttachable() { return true; }
            @Override public boolean canHaveAttached() { return true; }
            @Override public Connector getFront() { return front; }
            @Override public Connector getBack() { return back; }
        };
        handler.v = vehicle;
        when(vehicle.getTrainHandler()).thenReturn(handler);
        return handler;
    }

    private Connector connector(ActiveVehicle vehicle, ActiveModel model, String name, float z) {
        ModelBone bone = stub(ModelBone.class);
        BlueprintBone blueprint = new BlueprintBone();
        blueprint.setRotatedGlobalPosition(new Vector3f(0, 0, z));
        when(bone.getBlueprintBone()).thenReturn(blueprint);
        when(model.getBone(name)).thenReturn(Optional.of(bone));
        return new Connector(vehicle, new Connector(name));
    }

    private void wall(TrainHandler loco, BoundingBox obstacle) {
        World world = stub(World.class);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            int x = call.getArgument(0);
            int y = call.getArgument(1);
            int z = call.getArgument(2);
            Block block = stub(Block.class);
            BoundingBox cell = new BoundingBox(x, y, z, x + 1, y + 1, z + 1);
            boolean solid = cell.overlaps(obstacle);
            when(block.isPassable()).thenReturn(!solid);
            when(block.getLocation()).thenReturn(new Location(world, x, y, z));
            if (solid) {
                VoxelShape shape = stub(VoxelShape.class);
                when(shape.getBoundingBoxes()).thenReturn(List.of(
                        cell.intersection(obstacle).shift(-x, -y, -z)));
                when(block.getCollisionShape()).thenReturn(shape);
            }
            return block;
        });
        for (TrainHandler car = loco; car != null;
                car = car.hasChild() ? car.getChild().getTrainHandler() : null) {
            when(car.v.getEntity().getWorld()).thenReturn(world);
        }
    }

    private static void savedBoneYaw(TrainHandler loco, float yaw) {
        SimpleManualAnimator animator = stub(SimpleManualAnimator.class);
        when(animator.getRotation()).thenReturn(new Quaternionf().rotateYXZ((float) Math.toRadians(yaw), 0, 0));
        BoneRotator rotator = stub(BoneRotator.class);
        when(rotator.getAnimator()).thenReturn(animator);
        BehaviourHandler behaviour = stub(BehaviourHandler.class);
        when(behaviour.getRotator()).thenReturn(rotator);
        when(loco.v.getBehaviourHandler()).thenReturn(behaviour);
    }

    private static void inWorld(TrainHandler loco, String name) {
        World world = stub(World.class);
        when(world.getName()).thenReturn(name);
        when(loco.v.getEntity().getWorld()).thenReturn(world);
    }

    private static <T> T stub(Class<T> type) {
        return mock(type);
    }
}
