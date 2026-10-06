package net.tfminecraft.vehicleframework.database;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.*;
import java.util.stream.Stream;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.*;
import net.tfminecraft.vehicleframework.VehicleFramework;
import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.enums.SeatType;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.seat.Seat;

class DatabaseLoggingCoverageTest {
    @TempDir Path folder;
    private final World world = mock(World.class);
    private final List<ActiveVehicle> vehicles = new ArrayList<>();

    @BeforeEach void world() {
        when(world.getName()).thenReturn("harbour"); Chunk chunk = mock(Chunk.class);
        when(chunk.getX()).thenReturn(2); when(chunk.getZ()).thenReturn(-3); when(chunk.isLoaded()).thenReturn(true);
        when(world.getChunkAt(any(Location.class))).thenReturn(chunk);
        PersistenceLog.configure(false, folder.toFile());
    }
    @AfterEach void resetLogging() {
        PersistenceLog.configure(false, folder.toFile()); for (ActiveVehicle vehicle : vehicles) PersistenceLog.unload(vehicle, "test cleanup");
    }

    @Test void countingLogLinesClosesTheFilesystemStream() throws Exception {
        LogWriter log = new LogWriter(folder.toFile()); Path file = folder.resolve("logs/log.txt"); AtomicBoolean closed = new AtomicBoolean();
        try (Stream<String> stream = Files.lines(file).onClose(() -> closed.set(true)); MockedStatic<Files> files = mockStatic(Files.class, call -> {
            if (call.getMethod().getName().equals("lines") && call.getArguments().length == 1 && file.equals(call.getArgument(0))) return stream;
            return call.callRealMethod();
        })) {
            log.logEntry("resource probe");
            assertTrue(closed.get(), "Line-count stream must close after each logged entry");
        }
        assertTrue(Files.readString(file).contains("resource probe"));
    }

    @Test void logRotationPreservesAllLinesAndAvoidsSameDayArchiveCollisions() throws Exception {
        LogWriter writer = new LogWriter(folder.toFile()); Path current = folder.resolve("logs/log.txt");
        assertTrue(Files.exists(folder.resolve("logs/older"))); new LogWriter(folder.toFile());
        writer.logEntry("first"); assertTrue(Files.readString(current).contains("] first"));
        String date = new SimpleDateFormat("dd-MM-yyyy").format(new Date()); Path archived = folder.resolve("logs/older/log_" + date + ".txt");
        Files.writeString(current, "preserved\n".repeat(400)); writer.logEntry("last in first archive");
        assertEquals(401, Files.readAllLines(archived).size()); assertEquals(0, Files.size(current));
        Files.writeString(current, "second\n".repeat(400)); writer.logEntry("last in second archive");
        Path second = folder.resolve("logs/older/log_" + date + "_1.txt");
        assertEquals(401, Files.readAllLines(second).size()); assertEquals(0, Files.size(current));
        assertTrue(Files.readString(archived).contains("last in first archive"));
    }

    @Test void logWriteAndRotationFailuresPreserveTheExistingFiles() throws Exception {
        Files.createDirectories(folder.resolve("logs")); Files.writeString(folder.resolve("logs/older"), "directory obstruction");
        LogWriter writer = new LogWriter(folder.toFile()); Path current = folder.resolve("logs/log.txt");
        Files.writeString(current, "entry\n".repeat(400)); assertDoesNotThrow(() -> writer.logEntry("rotation fails"));
        assertEquals(401, Files.readAllLines(current).size()); assertEquals("directory obstruction", Files.readString(folder.resolve("logs/older")));
        Files.delete(current); Files.createDirectory(current); assertDoesNotThrow(() -> writer.logEntry("cannot append to directory"));
        Path obstruction = folder.resolve("plugin-is-file"); Files.writeString(obstruction, "do not replace");
        assertDoesNotThrow(() -> new LogWriter(obstruction.toFile())); assertEquals("do not replace", Files.readString(obstruction));
    }

    @Test void optionalCoreProtectIntegrationReportsEntryExitPlacementAndRemoval() {
        boolean previous = Cache.coreProtect; Player pilot = player(); ActiveVehicle vehicle = vehicle(); Block block = mock(Block.class);
        when(block.getLocation()).thenReturn(location(32, 64, -48)); when(block.getType()).thenReturn(Material.OAK_PLANKS);
        try (var framework = mockStatic(VehicleFramework.class, RETURNS_DEEP_STUBS)) {
            var core = VehicleFramework.getCoreProtect(); Cache.coreProtect = false;
            LogWriter.logEnter(pilot, vehicle); LogWriter.logExit(pilot, vehicle); LogWriter.logPlace("pilot", block); LogWriter.logBreak("pilot", block);
            verifyNoInteractions(core); Cache.coreProtect = true;
            LogWriter.logEnter(pilot, vehicle); LogWriter.logExit(pilot, vehicle); LogWriter.logPlace("pilot", block); LogWriter.logBreak("pilot", block);
            verify(core, times(2)).logInteraction("Pilot", vehicle.getEntity().getLocation()); verify(core).logChat(pilot, "+tug"); verify(core).logChat(pilot, "-tug");
            verify(core).logPlacement("pilot", block.getLocation(), Material.OAK_PLANKS, null); verify(core).logRemoval("pilot", block.getLocation(), Material.OAK_PLANKS, null);
        } finally { Cache.coreProtect = previous; }
    }

    @Test void persistenceLogAppendsAcrossConfigurationAndDescribesPositionsAndPlayers() throws Exception {
        assertFalse(PersistenceLog.isEnabled()); PersistenceLog.append("disabled"); assertFalse(Files.exists(logFile()));
        PersistenceLog.configure(true, folder.toFile()); assertTrue(PersistenceLog.isEnabled()); PersistenceLog.append(null);
        PersistenceLog.append("before reload"); PersistenceLog.configure(true, null); PersistenceLog.append("after reload");
        String log = log(); assertTrue(log.contains("before reload")); assertTrue(log.contains("after reload"));
        assertEquals("null", PersistenceLog.xyz(null)); assertEquals("null", PersistenceLog.xyz(new Location(null, 0, 0, 0)));
        assertEquals("1.250,2.500,-3.750", PersistenceLog.xyz(1.25, 2.5, -3.75));
        assertTrue(PersistenceLog.xyz(location(32.25, 64, -48.5)).contains("harbour 32.250,64.000,-48.500 yaw=0.000 pitch=0.000 chunk=2,-3 loaded=true"));
        assertEquals("player=null", PersistenceLog.player(null)); Player pilot = player(); assertTrue(PersistenceLog.player(pilot).contains("bukkitVehicle=none"));
        Entity mount = mock(Entity.class); UUID mountId = UUID.randomUUID(); when(mount.getUniqueId()).thenReturn(mountId); when(pilot.getVehicle()).thenReturn(mount);
        when(pilot.isInsideVehicle()).thenReturn(true); assertTrue(PersistenceLog.player(pilot).contains("inside=true bukkitVehicle=" + mountId));
        assertEquals("vehicle=null", PersistenceLog.vehicle(null)); ActiveVehicle vehicle = vehicle();
        assertTrue(PersistenceLog.vehicle(vehicle).contains("train=false")); when(vehicle.getSeatHandler()).thenReturn(null);
        when(vehicle.getEntity()).thenReturn(null); assertTrue(PersistenceLog.vehicle(vehicle).contains("entity=null dead=true train=false vfPassengers=0"));
    }

    @Test void trainSummariesUseLiveRelationsOrPendingIdentifiers() {
        ActiveVehicle vehicle = vehicle(); when(vehicle.isTrain()).thenReturn(true); var train = vehicle.getTrainHandler();
        UUID track = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        when(train.isBound()).thenReturn(true); when(train.getSplineId()).thenReturn(track); when(train.getS()).thenReturn(4.5); when(train.getTravelSign()).thenReturn(-1);
        when(train.getPendingParent()).thenReturn("pending-parent"); when(train.getPendingChild()).thenReturn("pending-child");
        String pending = PersistenceLog.vehicle(vehicle); assertTrue(pending.contains("bound=true spline=" + track + " s=4.500 sign=-1 parent=pending-parent child=pending-child"));
        when(vehicle.hasParent()).thenReturn(true); when(vehicle.getParent().getUUID()).thenReturn("live-parent");
        when(train.hasChild()).thenReturn(true); when(train.getChild().getUUID()).thenReturn("live-child");
        assertTrue(PersistenceLog.vehicle(vehicle).contains("parent=live-parent child=live-child liveParent=true liveChild=true"));
    }

    @Test void lifecycleAndMountEventsRecordActionDetailsAndSpawnCycle() throws Exception {
        PersistenceLog.configure(true, folder.toFile()); ActiveVehicle vehicle = vehicle(); Player pilot = player(); Seat seat = new Seat(SeatType.CAPTAIN, "driver");
        int before = PersistenceLog.spawnCycle(); PersistenceLog.spawnManagerStart(3); assertEquals(before + 1, PersistenceLog.spawnCycle());
        assertEquals(before + 2, PersistenceLog.nextSpawnCycle()); Location here = location(32, 64, -48);
        PersistenceLog.spawnLoad("cart.json", here); PersistenceLog.spawnQueued("cart.json", here, "harbour:2,-3"); PersistenceLog.spawned(vehicle, here);
        PersistenceLog.saveVehicle(vehicle, here); PersistenceLog.saveSpawn("cart.json", here);
        PersistenceLog.loadJson("cart.json", "uuid", "tug", null); PersistenceLog.loadJson("cart.json", "uuid", "tug", ConsistData.unbound());
        PersistenceLog.loadJson("cart.json", "uuid", "tug", new ConsistData("p", "c", "track", 4.5, -1));
        PersistenceLog.tryLink("reload", vehicle); PersistenceLog.mount(pilot, vehicle, seat, true, true); PersistenceLog.mount(null, null, null, false, false);
        PersistenceLog.remount(pilot, vehicle, "driver"); PersistenceLog.remount(vehicle.getEntity(), vehicle, "harness");
        PersistenceLog.dismount(pilot, vehicle, true); PersistenceLog.dismount(vehicle.getEntity(), vehicle, false);
        PersistenceLog.unload(null, "absent"); PersistenceLog.unload(vehicle, "chunk unloaded");
        String log = log();
        for (String marker : List.of("SPAWN_MANAGER_START", "SPAWN_LOAD", "SPAWN_QUEUED", "SPAWNED", "SAVE_VEHICLE", "SAVE_SPAWN", "LOAD_JSON", "TRY_LINK", "MOUNT", "REMOUNT", "DISMOUNT", "UNLOAD")) assertTrue(log.contains(marker), marker);
        assertTrue(log.contains("consist=parent=p child=c spline=track s=4.5 sign=-1")); assertTrue(log.contains("seat=driver type=CAPTAIN manager=true meSeatMap=true"));
    }

    @Test void posePlacementAndParticleLogsThrottleNoiseAndResetWhenVehicleUnloads() throws Exception {
        PersistenceLog.configure(true, folder.toFile()); ActiveVehicle vehicle = vehicle(); when(vehicle.getSpawnTime()).thenReturn(System.currentTimeMillis() - 30000);
        Location here = location(32, 64, -48), near = here.clone().add(.1, 0, 0), far = here.clone().add(2, 0, 0);
        PersistenceLog.placeCars(null); PersistenceLog.applyPose(null, "ignored", here, far); PersistenceLog.particleOffset(null, here, far);
        ActiveVehicle unidentified = mock(ActiveVehicle.class); PersistenceLog.particleOffset(unidentified, here, far);
        for (int i = 0; i < 22; i++) { PersistenceLog.placeCars(vehicle); PersistenceLog.applyPose(vehicle, "settle", here, near); }
        assertEquals(20, count("PLACE_CARS")); assertEquals(20, count("APPLY_POSE"));
        PersistenceLog.applyPose(vehicle, "large correction", here, far); assertEquals(21, count("APPLY_POSE"));
        PersistenceLog.particleOffset(vehicle, here, near); PersistenceLog.particleOffset(vehicle, here, far); PersistenceLog.particleOffset(vehicle, here, far);
        assertEquals(1, count("SMOKE_OFFSET")); PersistenceLog.unload(vehicle, "reload");
        PersistenceLog.placeCars(vehicle); PersistenceLog.applyPose(vehicle, "reload", here, near); PersistenceLog.particleOffset(vehicle, here, far);
        assertEquals(21, count("PLACE_CARS")); assertEquals(22, count("APPLY_POSE")); assertEquals(2, count("SMOKE_OFFSET"));
        PersistenceLog.applyPose(vehicle, "null endpoints", null, here); PersistenceLog.applyPose(vehicle, "worldless", new Location(null, 1, 2, 3), here);
        World other = mock(World.class); Chunk otherChunk = mock(Chunk.class); when(other.getChunkAt(any(Location.class))).thenReturn(otherChunk);
        PersistenceLog.applyPose(vehicle, "other world", new Location(other, 1, 2, 3), here); assertTrue(log().contains("gap=-1.000"));
        when(vehicle.getEntity()).thenReturn(null); PersistenceLog.applyPose(vehicle, "removed entity", here, far); assertTrue(log().contains("after=null"));
    }

    @Test void filesystemFailureIsReportedWithoutCrashingTheCaller() throws Exception {
        Path obstruction = folder.resolve("logs"); Files.writeString(obstruction, "keep this file");
        Logger logger = Logger.getLogger(PersistenceLog.class.getName()); List<LogRecord> records = new ArrayList<>();
        Handler capture = new Handler() { public void publish(LogRecord record) { records.add(record); } public void flush() {} public void close() {} };
        logger.addHandler(capture);
        try { assertDoesNotThrow(() -> PersistenceLog.configure(true, folder.toFile())); } finally { logger.removeHandler(capture); }
        assertTrue(records.stream().anyMatch(record -> record.getMessage().contains("Failed to write persistence.log")));
        assertEquals("keep this file", Files.readString(obstruction));
    }

    private ActiveVehicle vehicle() {
        ActiveVehicle vehicle = mock(ActiveVehicle.class, RETURNS_DEEP_STUBS); Entity entity = mock(Entity.class);
        when(vehicle.getUUID()).thenReturn(UUID.randomUUID().toString()); when(vehicle.getId()).thenReturn("tug"); when(vehicle.getName()).thenReturn("Tug");
        when(vehicle.getSpawnTime()).thenReturn(System.currentTimeMillis()); when(vehicle.getEntity()).thenReturn(entity);
        when(entity.getUniqueId()).thenReturn(UUID.randomUUID()); when(entity.getLocation()).thenReturn(location(32, 64, -48)); when(entity.getPassengers()).thenReturn(List.of());
        when(vehicle.getSeatHandler().getPassengers()).thenReturn(List.of()); vehicles.add(vehicle); return vehicle;
    }
    private Player player() {
        Player player = mock(Player.class); when(player.getName()).thenReturn("Pilot"); when(player.getLocation()).thenReturn(location(32, 64, -48)); return player;
    }
    private Location location(double x, double y, double z) { return new Location(world, x, y, z); }
    private Path logFile() { return folder.resolve("logs/persistence.log"); }
    private String log() throws IOException { return Files.readString(logFile()); }
    private long count(String marker) throws IOException { return log().lines().filter(line -> line.contains(marker)).count(); }
}
