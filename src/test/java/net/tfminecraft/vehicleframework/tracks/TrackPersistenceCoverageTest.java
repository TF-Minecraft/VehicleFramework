package net.tfminecraft.vehicleframework.tracks;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import net.tfminecraft.vehicleframework.VFLogger;
import net.tfminecraft.vehicleframework.VehicleFramework;
import net.tfminecraft.vehicleframework.cache.Cache;

class TrackPersistenceCoverageTest {
    @TempDir Path directory;

    @Test void storeSkipsBadFilesAndReportsFailedWritesAndDeletes() throws Exception {
        try (var logger = mockStatic(VFLogger.class)) {
            TrackStore store = new TrackStore(directory.toFile());
            assertTrue(store.loadAll().isEmpty()); assertTrue(store.loadAllJunctions().isEmpty());
            Path root = directory.resolve("data/tracks"); Files.createDirectories(root);
            Files.writeString(root.resolve("not-a-world"), "text");
            Path world = root.resolve("world"); Files.createDirectories(world);
            Files.writeString(world.resolve("invalid.json"), "{"); Files.writeString(world.resolve("array.json"), "[]");
            Path junctions = world.resolve("junctions"); Files.createDirectories(junctions);
            Files.writeString(junctions.resolve("invalid.json"), "{"); Files.writeString(junctions.resolve("array.json"), "[]");
            assertTrue(store.loadAll().isEmpty()); assertTrue(store.loadAllJunctions().isEmpty());
            TrackSpline track = line(0, 20, 0);
            Path blocked = store.fileFor(track).toPath(); Files.createDirectories(blocked); Files.writeString(blocked.resolve("child"), "keep");
            store.delete(track); assertTrue(Files.isDirectory(blocked));
            store.save(track); assertTrue(Files.isDirectory(blocked));
            TrackJunction junction = node(track, 8, null);
            Path blockedJunction = store.junctionFileFor("world", junction.id).toPath();
            Files.createDirectories(blockedJunction); Files.writeString(blockedJunction.resolve("child"), "keep");
            store.deleteJunction("world", junction.id); store.saveJunction("world", junction);
            assertTrue(Files.isDirectory(blockedJunction));
            logger.verify(() -> VFLogger.log(contains("Failed to save track")));
            logger.verify(() -> VFLogger.log(contains("Failed to save junction")));
            logger.verify(() -> VFLogger.log(contains("Failed to delete track")));
            logger.verify(() -> VFLogger.log(contains("Failed to delete junction")));
        }
    }

    @Test void unreadableDirectoriesDoNotEraseOtherWorlds() throws Exception {
        TrackStore store = new TrackStore(directory.toFile());
        TrackSpline track = line(0, 20, 0); store.save(track);
        Path root = directory.resolve("data/tracks"), world = root.resolve("world"), junctions = world.resolve("junctions");
        Files.createDirectories(junctions);
        for (Path unreadable : List.of(root, world, junctions)) {
            Set<PosixFilePermission> old = Files.getPosixFilePermissions(unreadable);
            try {
                Files.setPosixFilePermissions(unreadable, Set.of());
                assertTrue(store.loadAllJunctions().isEmpty());
                if (!unreadable.equals(junctions)) assertTrue(store.loadAll().isEmpty());
            } finally { Files.setPosixFilePermissions(unreadable, old); }
        }
        assertEquals(track.getId(), store.loadAll().getFirst().getId());
        TrackStore notDirectory = new TrackStore(directory.resolve("file-root").toFile());
        Files.createDirectories(directory.resolve("file-root/data"));
        Files.writeString(directory.resolve("file-root/data/tracks"), "file");
        assertTrue(notDirectory.loadAll().isEmpty()); assertTrue(notDirectory.loadAllJunctions().isEmpty());
    }

    @Test void closeWaitsThroughInterruptAndRestoresInterruptFlag() throws Exception {
        ExecutorService executor = mock(ExecutorService.class);
        when(executor.awaitTermination(10, TimeUnit.SECONDS)).thenThrow(new InterruptedException()).thenReturn(false, true);
        try (var executors = mockStatic(Executors.class); var logger = mockStatic(VFLogger.class)) {
            executors.when(() -> Executors.newSingleThreadExecutor(any(ThreadFactory.class))).thenReturn(executor);
            TrackStore store = new TrackStore(directory.toFile()); store.writeInBackground(); store.writeInBackground();
            store.close();
            assertTrue(Thread.currentThread().isInterrupted()); verify(executor).shutdown();
            verify(executor, times(3)).awaitTermination(10, TimeUnit.SECONDS);
            logger.verify(() -> VFLogger.log("Still waiting for track saves to finish"));
            store.close();
        } finally { Thread.interrupted(); }
    }

    @Test void registryPersistsPaidPrefixesRemovesEmptyTracksAndNotifiesDisplays() throws Exception {
        TrackRegistry registry = new TrackRegistry(directory.toFile());
        TrackDisplayManager displays = mock(TrackDisplayManager.class);
        try (var framework = mockStatic(VehicleFramework.class)) {
            framework.when(VehicleFramework::getTrackDisplayManager).thenReturn(displays);
            TrackSpline original = registry.createBetween("world", 0, 64, 0, 0, 64, 40);
            assertThrows(IllegalArgumentException.class, () -> registry.createBetween("other", 0, 64, 0, 0, 64, 0));
            registry.persistPoints(UUID.randomUUID(), null);
            registry.persistPoints(original.getId(), List.of(new double[]{0,64,0}, new double[]{0,64,12}));
            assertEquals(12, registry.get(original.getId()).orElseThrow().length());
            assertEquals(12, new TrackStore(directory.toFile()).loadAll().getFirst().length());
            registry.invalidateAllVisuals();
            assertEquals(12, registry.shortestRouteLength("world", 0, 0, 0, 0, 12, 0).orElseThrow(), 1e-8);
            registry.persistPoints(original.getId(), null);
            assertTrue(registry.get(original.getId()).isEmpty()); verify(displays).despawnSpline(original.getId());
            assertFalse(registry.delete(original.getId()));
        }
    }

    @Test void junctionValidationAndIncompleteCleanupKeepFilesAndDisplaysConsistent() throws Exception {
        TrackRegistry registry = new TrackRegistry(directory.toFile());
        TrackDisplayManager displays = mock(TrackDisplayManager.class);
        try (var framework = mockStatic(VehicleFramework.class)) {
            framework.when(VehicleFramework::getTrackDisplayManager).thenReturn(displays);
            TrackSpline stem = registry.replace(line(0, 80, 0));
            TrackSpline other = registry.replace(line(0, 80, 100));
            assertThrows(TrackLayException.class, () -> registry.putJunction(null));
            assertThrows(TrackLayException.class, () -> registry.putJunction(node(line(0, 80, 200), 8, null)));
            assertThrows(TrackLayException.class, () -> registry.ensureFrogClear(null, 0, null));
            assertThrows(TrackLayException.class, () -> registry.attachBranch(null, UUID.randomUUID()));
            assertThrows(TrackLayException.class, () -> registry.attachBranch(UUID.randomUUID(), UUID.randomUUID()));
            assertTrue(registry.junctionByBranch(null).isEmpty()); assertFalse(registry.setThrown(UUID.randomUUID(), true));
            TrackJunction empty = registry.putJunction(node(stem, 8, null));
            TrackJunction complete = registry.putJunction(node(stem, 30, UUID.randomUUID()));
            registry.putJunction(node(other, 30, UUID.randomUUID()));
            registry.ensureFrogClear(stem, 8, empty.id);
            assertThrows(TrackLayException.class, () -> registry.ensureFrogClear(stem, 30, null));
            assertThrows(TrackLayException.class, () -> registry.ensureFrogClear(stem, 35, null));
            assertThrows(TrackLayException.class, () -> registry.putJunction(complete.withBranch(UUID.randomUUID())));
            assertThrows(TrackLayException.class, () -> registry.putJunction(node(stem, 31, UUID.randomUUID())));
            registry.dropIncompleteJunctions();
            assertTrue(registry.getJunction(empty.id).isEmpty()); assertTrue(registry.getJunction(complete.id).isPresent());
            assertFalse(new TrackStore(directory.toFile()).junctionFileFor("world", empty.id).exists());
            verify(displays).despawnSwitch(empty.id);
            assertFalse(registry.deleteJunction(UUID.randomUUID()));
        }
    }

    @Test void diggingSparseTracksReturnsActualSurvivingPieces() {
        TrackRegistry registry = new TrackRegistry(directory.toFile());
        TrackSpline pair = registry.replace(line(0, 10, 0));
        assertEquals(DigResult.Kind.NONE, registry.digAt(pair, -1).kind);
        assertEquals(DigResult.Kind.DELETED, registry.digAt(pair, 0).kind);
        TrackSpline three = registry.replace(TrackSpline.fromPoints(UUID.randomUUID(), "world", false,
            List.of(new double[]{0,64,0}, new double[]{0,64,5}, new double[]{0,64,10})));
        assertEquals(DigResult.Kind.DELETED, registry.digAt(three, 1).kind);
        TrackSpline four = registry.replace(TrackSpline.fromPoints(UUID.randomUUID(), "world", false,
            List.of(new double[]{0,64,0}, new double[]{0,64,5}, new double[]{0,64,10}, new double[]{0,64,15})));
        DigResult tail = registry.digAt(four, 1);
        assertEquals(DigResult.Kind.UPDATED, tail.kind); assertEquals(5, tail.kept.length());
        assertNotEquals(four.getId(), tail.kept.getId());
        assertTrue(registry.digTarget("absent", 0, 64, 0).isEmpty());
    }

    @Test void extendingAtSecondEndpointAndReverseJunctionsPersistTheResult() throws Exception {
        TrackRegistry registry = new TrackRegistry(directory.toFile());
        TrackSpline stem = registry.replace(line(0, 40, 0));
        TrackLayResult extended = registry.lay("world", 0, 64, 60, 0, 64, 40);
        assertEquals(TrackLayResult.Kind.APPEND, extended.kind); assertEquals(60, extended.spline().length(), 1e-6);
        TrackLayResult prepended = registry.lay("world", 0,64,-20,0,64,0);
        assertEquals(TrackLayResult.Kind.PREPEND, prepended.kind); assertEquals(-20,prepended.spline().first().z,1e-6);
        assertThrows(TrackLayException.class, () -> registry.layBranch(UUID.randomUUID(), 10, 1, "world", null, 3, 64, 25));
        assertThrows(TrackLayException.class, () -> registry.layBranch(UUID.randomUUID(), "world", null, 3, 64, 25));
        TrackSpline branch = registry.layBranch(stem.getId(), 60, -1, "world", null, 3, 64, 20);
        TrackJunction junction = registry.junctionByBranch(branch.getId()).orElseThrow();
        assertEquals(-1, junction.facingSign);
        assertThrows(TrackLayException.class, () -> registry.layBranch(junction.id, "world", null, 3, 64, 20));
        assertTrue(registry.digTarget("world", branch.first().x, 64, branch.first().z).isPresent());
    }

    @Test void legacyClosedPolylinesArePromotedAndForwardLoopClosurePreservesDirection() throws Exception {
        TrackStore store = new TrackStore(directory.toFile());
        TrackSpline closed = TrackSpline.fromPoints(UUID.randomUUID(), "legacy", false,
            List.of(new double[]{0,64,0},new double[]{20,64,0},new double[]{20,64,20},new double[]{0,64,0}));
        store.save(closed);
        TrackRegistry registry = new TrackRegistry(directory.toFile()); registry.loadFromDisk();
        assertTrue(registry.get(closed.getId()).orElseThrow().isLoop());
        assertTrue(store.loadAll().getFirst().isLoop());
        TrackSpline arc = registry.replace(openArc());
        TrackDisplayManager displays = mock(TrackDisplayManager.class);
        try (var framework = mockStatic(VehicleFramework.class)) {
            framework.when(VehicleFramework::getTrackDisplayManager).thenReturn(displays);
            TrackSpline loop = registry.lay("world", arc.last().x,64,arc.last().z,arc.first().x,64,arc.first().z).spline();
            assertTrue(loop.isLoop()); assertEquals(arc.first().z, loop.first().z);
            verify(displays).despawnSpline(arc.getId());
            assertTrue(registry.digTarget("world", loop.first().x,64,loop.first().z).orElseThrow().spans().getFirst().halfSpan() > 0);
        }
    }

    @Test void diggingEndpointsAndLargeGapsAccountsForJunctionProtection() throws Exception {
        TrackRegistry registry = new TrackRegistry(directory.toFile());
        TrackSpline branch = registry.replace(line(0, 10, 100));
        TrackSpline pair = registry.replace(line(0, 40, 0));
        registry.putJunction(node(pair, 8, branch.getId()));
        assertEquals(1, registry.digTarget("world",0,64,0).orElseThrow().spans().size());
        registry.delete(pair.getId());
        TrackSpline spaced = registry.replace(TrackSpline.fromPoints(UUID.randomUUID(), "world", false,
            List.of(new double[]{0,64,0},new double[]{0,64,10},new double[]{0,64,40},new double[]{0,64,50})));
        TrackJunction middle = registry.putJunction(node(spaced, 20, branch.getId()));
        assertEquals(1, registry.digTarget("world",0,64,0).orElseThrow().spans().size());
        assertEquals(1, registry.digTarget("world",0,64,50).orElseThrow().spans().size());
        registry.digAt(spaced, 1);
        assertTrue(registry.getJunction(middle.id).isEmpty());
    }

    @Test void joiningAnExistingBranchClearsTheDroppedBranchReference() throws Exception {
        TrackRegistry registry = new TrackRegistry(directory.toFile());
        TrackSpline stem = registry.replace(line(0,80,100));
        TrackSpline keep = registry.replace(line(0,20,0));
        TrackSpline drop = registry.replace(line(30,50,0));
        TrackJunction junction = registry.putJunction(node(stem,20,drop.getId()));
        registry.lay("world",0,64,20,0,64,30);
        assertTrue(registry.get(drop.getId()).isEmpty());
        assertTrue(registry.get(keep.getId()).isPresent());
        assertTrue(registry.getJunction(junction.id).isEmpty());
        assertFalse(new TrackStore(directory.toFile()).junctionFileFor("world",junction.id).exists());
    }

    @Test void aRejectedJunctionRegistrationRollsBackItsNewTrackFile() throws Exception {
        TrackRegistry registry = spy(new TrackRegistry(directory.toFile()));
        TrackSpline stem = registry.replace(TrackSpline.fromPoints(UUID.randomUUID(),"world",false,
            List.of(new double[]{0,64,0},new double[]{0,64,20},new double[]{0,64,40},new double[]{0,64,80})));
        TrackJunction pending = registry.putJunction(new TrackJunction(UUID.randomUUID(),stem.getId(),40,-1,TrackJunction.Side.RIGHT,null));
        doThrow(new TrackLayException("registration refused")).when(registry).putJunction(any());
        assertThrows(TrackLayException.class, () -> registry.layBranch(pending.id,"world",null,3,64,20));
        assertEquals(1, registry.all().size()); assertEquals(1,new TrackStore(directory.toFile()).loadAll().size());
        assertThrows(TrackLayException.class, () -> registry.layBranch(stem.getId(),40,-1,"world",null,3,64,20));
        assertEquals(1, registry.all().size()); assertEquals(1,new TrackStore(directory.toFile()).loadAll().size());
        registry.digAt(stem,0);
        assertTrue(registry.getJunction(pending.id).isEmpty());
    }

    @Test void steepLegacyEndpointsAndElevatedTracksRetainTheirOwnGeometry() throws Exception {
        TrackRegistry registry = new TrackRegistry(directory.toFile());
        TrackSpline vertical = TrackSpline.fromPoints(UUID.randomUUID(),"vertical",false,
            List.of(new double[]{0,64,0},new double[]{0,74,0}));
        registry.replace(vertical);
        assertTrue(registry.lay("vertical",0,74,0,0,74,20).spline().length()>20);
        TrackSpline ground = registry.replace(line(0,40,100));
        TrackSpline bridge = TrackSpline.fromPoints(UUID.randomUUID(),"world",false,
            List.of(new double[]{100,70,0},new double[]{100,70,10}));
        registry.replace(bridge); registry.pruneNestedShortTracks();
        assertTrue(registry.get(ground.getId()).isPresent()); assertTrue(registry.get(bridge.getId()).isPresent());
    }

    @Test void branchBudgetMeasuresTheCurveAndWorldClearanceIsCheckedBeforeRegistration() throws Exception {
        TrackRegistry registry = new TrackRegistry(directory.toFile());
        TrackSpline stem = registry.replace(line(0,80,0));
        List<double[]> points = TrackCurve.lay(0,64,40,180,3,64,20,Cache.trackMinLayDistance,
            Cache.trackMaxTurnDegrees,Cache.trackDesiredGradeDegrees,Cache.trackMaxGradeDegrees,TrackGenerate.STEP);
        double chord=Math.hypot(3,20), length=TrackSpline.fromPoints(UUID.randomUUID(),"world",false,points).length();
        assertTrue(length>chord+1e-6);
        double previous=Cache.trackMaxJunctionLength;
        try {
            Cache.trackMaxJunctionLength=(length+chord)/2;
            assertTrue(assertThrows(TrackLayException.class,()->registry.layBranch(stem.getId(),40,-1,"world",null,3,64,20)).getMessage().contains("at most"));
            assertEquals(1,registry.all().size());
        } finally { Cache.trackMaxJunctionLength=previous; }
        org.bukkit.World world=mock(org.bukkit.World.class);
        try (var clearance=mockStatic(TrackClearance.class)) {
            registry.layBranch(stem.getId(),40,-1,"world",world,3,64,20);
            clearance.verify(()->TrackClearance.check(eq(world),anyList(),eq(registry),anySet()));
        }
    }

    @Test void diggingLeavesNoJunctionOnASurvivingPieceTooShortToHoldIt() throws Exception {
        TrackRegistry registry = new TrackRegistry(directory.toFile());
        TrackSpline stem=registry.replace(TrackSpline.fromPoints(UUID.randomUUID(),"world",false,
            List.of(new double[]{0,64,0},new double[]{0,64,3},new double[]{0,64,8},new double[]{0,64,20})));
        TrackJunction pending=registry.putJunction(node(stem,1,null));
        registry.digAt(stem,2);
        assertTrue(registry.getJunction(pending.id).isEmpty());
        assertFalse(new TrackStore(directory.toFile()).junctionFileFor("world",pending.id).exists());
    }

    static TrackSpline openArc() {
        List<double[]> points = new ArrayList<>();
        for (int z=10;z<=40;z++) points.add(new double[]{0,64,z});
        for (int i=1;i<63;i++) { double a=Math.PI-Math.PI*i/63; points.add(new double[]{20+20*Math.cos(a),64,40+20*Math.sin(a)}); }
        for (int z=39;z>=0;z--) points.add(new double[]{40,64,z});
        for (int i=1;i<63;i++) { double a=-Math.PI*i/63; points.add(new double[]{20+20*Math.cos(a),64,20*Math.sin(a)}); }
        return TrackSpline.fromPoints(UUID.randomUUID(),"world",false,points);
    }

    static TrackSpline line(double start, double end, double x) {
        return TrackSpline.fromPoints(UUID.randomUUID(), "world", false, List.of(new double[]{x,64,start}, new double[]{x,64,end}));
    }
    static TrackJunction node(TrackSpline stem, double s, UUID branch) {
        return new TrackJunction(UUID.randomUUID(), stem.getId(), s, 1, TrackJunction.Side.RIGHT, branch);
    }
}
