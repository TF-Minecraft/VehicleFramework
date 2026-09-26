package net.tfminecraft.vehicleframework.vehicles.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;
import org.joml.Quaternionf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.ticxo.modelengine.api.model.bone.SimpleManualAnimator;

import net.tfminecraft.vehicleframework.VehicleFramework;
import net.tfminecraft.vehicleframework.bones.BoneRotator;
import net.tfminecraft.vehicleframework.database.ConsistData;
import net.tfminecraft.vehicleframework.database.VehicleRepository;
import net.tfminecraft.vehicleframework.database.VehicleSnapshot;
import net.tfminecraft.vehicleframework.tracks.TrackJunction;
import net.tfminecraft.vehicleframework.tracks.TrackPose;
import net.tfminecraft.vehicleframework.tracks.TrackRegistry;
import net.tfminecraft.vehicleframework.tracks.TrackSpline;
import net.tfminecraft.vehicleframework.tracks.TrackStore;
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

    @BeforeEach
    void setUp() throws Exception {
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
    }

    @Test
    void forwardStopReverseKeepsBothCarsOnTheirCoupledSide() {
        TrackSpline track = straightTrack(false);
        TrainHandler loco = consist(track, 60);
        assertSequence(loco, track, new double[]{60.2, 60.2, 60.19, 60.19, 60.39});
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
        wall(loco, new BoundingBox(-1, 64, 39, 1, 68, 40));
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

    @Test
    void forwardLocomotiveWallStopsAllCarsAndAllowsReverse() {
        TrackSpline track = straightTrack(false);
        TrainHandler loco = consist(track, 60.5);
        wall(loco, new BoundingBox(-1, 64, 61, 1, 68, 62));
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
        BoundingBox obstacle = new BoundingBox(-1, 64, 36.45, 1, 68, 36.55);
        wall(loco, obstacle);
        loco.v.getAccessPanel().setSpeed(-8);
        loco.splineTick();
        TrainHandler last = loco.getChild().getTrainHandler().getChild().getTrainHandler();
        assertTrue(last.getS() >= 37.05 - 1e-8, "Last car must stop before the thin wall");
        assertFalse(last.v.getEntity().getBoundingBox().overlaps(obstacle));
        assertPositions(loco, track, loco.getS());
    }

    @Test
    void wallAtMiddleCarAlsoStopsWholeTrain() {
        TrackSpline track = straightTrack(false);
        TrainHandler loco = consist(track, 60.5);
        wall(loco, new BoundingBox(-1, 64, 49, 1, 68, 50));
        loco.v.getAccessPanel().setSpeed(-0.1);
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

    @Test
    void wallStopKeepsThrottleSetting() {
        TrackSpline track = straightTrack(false);
        TrainHandler loco = consist(track, 60.5);
        wall(loco, new BoundingBox(-1, 64, 61, 1, 68, 62));
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
        wall(loco, new BoundingBox(10, 64, 39, 11, 68, 41));
        loco.v.getAccessPanel().setSpeed(-30);
        loco.splineTick();
        assertTrue(loco.getS() >= 71.5 - 1e-8);
        assertTrue(loco.getS() < 80);
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
        wall(loco, new BoundingBox(-1, 64, 50.45, 1, 68, 50.6));
        loco.v.getAccessPanel().setSpeed(0.2);
        loco.splineTick();
        assertPositions(loco, stem, 49.9);
        assertNull(loco.toConsistData().getJunctionId());

        wall(loco, new BoundingBox(200, 64, 200, 201, 68, 201));
        loco.splineTick();
        assertEquals(branch.getId(), loco.getSplineId());
        assertEquals(junction.id.toString(), loco.toConsistData().getJunctionId());
        assertEquals(40, loco.getChild().getTrainHandler().getS(), 1e-8);
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
        wall(loco, new BoundingBox(-1, 64, 31.5, 1, 68, 32.5));
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
        assertEquals(33, last.v.getEntity().getLocation().getZ(), 1e-8);
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
        assertEquals(0, loco.getChild().getTrainHandler().getS(), 1e-8);
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
            assertEquals(track.sampleAt(expected).x, car.v.getEntity().getLocation().getX(), 1e-8);
            assertEquals(track.sampleAt(expected).z, car.v.getEntity().getLocation().getZ(), 1e-8);
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
        Connector connector = stub(Connector.class);
        when(connector.getOffset()).thenAnswer(call -> new Vector(0, 0, 5));
        TrainHandler handler = new TrainHandler(new YamlConfiguration()) {
            @Override public boolean isAttachable() { return true; }
            @Override public boolean canHaveAttached() { return true; }
            @Override public Connector getFront() { return connector; }
            @Override public Connector getBack() { return connector; }
        };
        handler.v = vehicle;
        when(vehicle.getTrainHandler()).thenReturn(handler);
        return handler;
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
