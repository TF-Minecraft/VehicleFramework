package net.tfminecraft.vehicleframework.database;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.google.gson.JsonParser;
import net.tfminecraft.vehicleframework.VFLogger;
import net.tfminecraft.vehicleframework.VehicleFramework;
import net.tfminecraft.vehicleframework.data.HealthData;
import net.tfminecraft.vehicleframework.enums.Component;
import net.tfminecraft.vehicleframework.loaders.VehicleLoader;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.Vehicle;
import net.tfminecraft.vehicleframework.vehicles.component.VehicleComponent;

class DatabaseRuntimeCoverageTest {
    @TempDir Path directory;
    static final String ID = VehiclePayloadCoverageTest.ID;
    static final String PAYLOAD = "{\"id\":\"cart\",\"owner\":\"player_Alice\",\"whitelisted\":true,\"whitelist\":[\"Bob\"],\"components\":{\"hull\":{\"damage\":5}}}";

    @Test void liveSaveReturnsPreciseStoredSavedAndFailureOutcomes() {
        VehicleRepository repository = mock(VehicleRepository.class);
        VehiclePersistence persistence = new VehiclePersistence(repository);
        ActiveVehicle live = VehiclePayloadCoverageTest.liveVehicle();
        assertSame(repository, persistence.repository());
        when(repository.saveLive(any())).thenReturn(true);
        assertTrue(persistence.saveLive(live));
        when(repository.saveLive(any())).thenReturn(false);
        assertTrue(persistence.saveLiveResult(live).isFailed());
        when(repository.findLive(ID)).thenReturn(Optional.of(snapshot(PAYLOAD)));
        assertTrue(persistence.saveLiveResult(live).isAlreadyStored());
        doThrow(new IllegalStateException()).when(repository).saveLive(any());
        assertEquals("SQLite save failed", persistence.saveLiveResult(live).reason());
        doThrow(new IllegalStateException("locked")).when(repository).saveLive(any());
        assertEquals("SQLite save failed: locked", persistence.saveLiveResult(live).reason());
        when(live.getEntity()).thenReturn(null);
        assertEquals("entity invalid", persistence.saveLiveResult(live).reason());
        assertTrue(persistence.saveLiveResult(null).isFailed());
        doThrow(new IllegalStateException()).when(repository).findLive(ID);
        assertTrue(persistence.resolveFailedLiveSave(ID, " ").isFailed());
        assertTrue(persistence.resolveFailedLiveSave(" ", null).reason().contains("unknown"));
        assertTrue(VehiclePersistResult.failed(null).isFailed());
        assertEquals("unknown", VehiclePersistResult.failed(" ").reason());
        try (var framework = mockStatic(VehicleFramework.class)) {
            assertNull(VehiclePersistence.current());
            framework.when(VehicleFramework::getVehicleRepository).thenReturn(repository);
            assertSame(repository, VehiclePersistence.current().repository());
        }
    }

    @Test void missingRepositoryAndClosedDatabaseFailWithoutMutatingOrThrowing() {
        VehiclePersistence missing = new VehiclePersistence(null);
        assertTrue(missing.saveLiveResult(null).isFailed());
        assertFalse(missing.saveLive(snapshot(PAYLOAD)));
        assertFalse(missing.tombstone(ID));
        assertFalse(missing.checkpointWal(true));
        assertFalse(missing.vacuumIntoBackup());
        VehicleRepository repository = VehicleRepository.open(directory.resolve("closed.db").toFile());
        repository.saveLive(snapshot(PAYLOAD));
        repository.close();
        VehiclePersistence closed = new VehiclePersistence(repository);
        try (var logger = mockStatic(VFLogger.class)) {
            logger.when(() -> VFLogger.log(anyString())).thenThrow(new IllegalStateException("logger unavailable"));
            assertTrue(closed.loadIncomplete(ID).isEmpty());
            assertFalse(closed.tombstone(ID));
            assertFalse(closed.checkpointWal(false));
            assertTrue(closed.findChunk("world", 0, 0).isEmpty());
            assertTrue(closed.findLive(ID).isEmpty());
            assertTrue(closed.countByOwner("player_Alice", List.of()).isEmpty());
            assertTrue(closed.listByOwner("player_Alice").isEmpty());
            assertTrue(closed.listPlayerOwned().isEmpty());
            assertTrue(closed.readMeta(ID).isEmpty());
            assertFalse(closed.clearOwnership(ID));
            assertFalse(closed.applyStoredDecay(ID, .1, .03));
            assertFalse(closed.saveLive(snapshot(PAYLOAD)));
        }
        VehicleRepository broken = mock(VehicleRepository.class);
        when(broken.hasLiveInChunk(anyString(), anyInt(), anyInt())).thenThrow(new IllegalStateException());
        assertFalse(new VehiclePersistence(broken).hasLiveInChunk("world", 0, 0));
    }

    @Test void ownershipAndDecayChangesCommitTogetherAndSurviveRestart() {
        Path file = directory.resolve("mutations.db");
        VehicleRepository repository = VehicleRepository.open(file.toFile());
        try (var templates = mockStatic(VehicleLoader.class)) {
            VehiclePersistence persistence = new VehiclePersistence(repository);
            assertFalse(persistence.clearOwnership("missing"));
            assertFalse(persistence.applyStoredDecay("missing", .1, .03));
            assertTrue(persistence.loadIncomplete("missing").isEmpty());
            assertFalse(persistence.tombstone(null)); assertFalse(persistence.tombstone(" "));
            repository.saveLive(snapshot(PAYLOAD));
            assertFalse(persistence.applyStoredDecay(ID, .1, .03));
            Vehicle template = mock(Vehicle.class, RETURNS_DEEP_STUBS);
            VehicleComponent hull = mock(VehicleComponent.class);
            when(hull.getType()).thenReturn(Component.HULL); when(hull.getHealthData()).thenReturn(new HealthData(100, 0, 1));
            when(template.getComponentHandler().getComponents()).thenReturn(List.of(hull));
            templates.when(() -> VehicleLoader.getByString("cart")).thenReturn(template);
            assertTrue(persistence.applyStoredDecay(ID, 0, .03));
            assertEquals(1, repository.findLive(ID).orElseThrow().getRevision());
            assertTrue(persistence.applyStoredDecay(ID, .1, .03));
            assertTrue(persistence.clearOwnership(ID));
            assertTrue(persistence.checkpointWal(false)); assertTrue(persistence.checkpointWal(true));
        } finally { repository.close(); }
        repository = VehicleRepository.open(file.toFile());
        try {
            VehicleSnapshot row = repository.findLive(ID).orElseThrow();
            var payload = JsonParser.parseString(row.getPayloadJson()).getAsJsonObject();
            assertEquals(3, row.getRevision()); assertEquals("none", row.getOwner());
            assertEquals("none", payload.get("owner").getAsString());
            assertFalse(payload.get("whitelisted").getAsBoolean());
            assertTrue(payload.getAsJsonArray("whitelist").isEmpty());
            assertEquals(15, payload.getAsJsonObject("components").getAsJsonObject("hull").get("damage").getAsDouble());
            repository.saveLive(snapshot("{"));
            VehiclePersistence persistence = new VehiclePersistence(repository);
            assertFalse(persistence.clearOwnership(ID));
            assertFalse(persistence.applyStoredDecay(ID, .1, .03));
            assertEquals("{", repository.findLive(ID).orElseThrow().getPayloadJson());
        } finally { repository.close(); }
    }

    @Test void tombstoneAndBackupFailuresKeepTheirReturnContracts() {
        VehicleRepository repository = mock(VehicleRepository.class);
        VehiclePersistence persistence = new VehiclePersistence(repository);
        when(repository.find(ID)).thenReturn(Optional.of(snapshot(PAYLOAD)));
        assertFalse(persistence.tombstone(ID));
        VehicleFramework previous = VehicleFramework.plugin;
        try {
            VehicleFramework.plugin = null;
            assertFalse(persistence.vacuumIntoBackup());
            VehicleFramework.plugin = mock(VehicleFramework.class);
            assertFalse(persistence.vacuumIntoBackup());
            when(VehicleFramework.plugin.getDataFolder()).thenReturn(directory.toFile());
            when(repository.vacuumIntoBackup(any())).thenReturn(true);
            assertTrue(persistence.vacuumIntoBackup());
            doThrow(new IllegalStateException()).when(repository).vacuumIntoBackup(any());
            assertFalse(persistence.vacuumIntoBackup());
        } finally { VehicleFramework.plugin = previous; }
    }

    @Test void repositoryFiltersInvalidRowsAndRebuildsOccupancyOnRollback() throws Exception {
        VehicleRepository repository = VehicleRepository.open(directory.resolve("filters.db").toFile());
        try {
            repository.upsert(null); assertFalse(repository.saveLive(null));
            VehicleSnapshot invalid = new VehicleSnapshot(null, "cart", null, null, "world", 0, 0, 0, 0, 0, 0, "{}", 2, 1, false, 1);
            repository.upsert(invalid); assertFalse(repository.saveLive(invalid));
            for (String absent : Arrays.asList(null, " ")) {
                assertTrue(repository.find(absent).isEmpty());
                assertTrue(repository.findLive(absent).isEmpty());
                assertTrue(repository.findNear(absent,0,0,1).isEmpty());
                assertTrue(repository.listLiveByOwner(absent).isEmpty());
                assertEquals(0,repository.tombstone(absent));
                assertEquals(0,repository.tombstone(absent,1,1));
            }
            repository.runInTransaction(null);
            repository.upsert(new VehicleSnapshot("legacy-untyped", " ", "Unknown", "player_Alice", "world", 0,64,0,0,0,0,"{}",1,1,false,1));
            repository.saveLive(snapshot(PAYLOAD));
            assertTrue(repository.countLiveByOwner(null, null).isEmpty());
            assertTrue(repository.countLiveByOwner(" ", null).isEmpty());
            assertTrue(repository.countLiveByOwner("player_Alice", Arrays.asList(null, " ", ID.toUpperCase(Locale.ROOT))).isEmpty());
            assertEquals(Map.of("cart", 1), repository.countLiveByOwner("player_Alice", null));
            assertThrows(IllegalStateException.class, () -> repository.runInTransaction(() -> {
                repository.tombstone(ID);
                throw new IllegalStateException("rollback");
            }));
            assertTrue(repository.hasLiveInChunk("world", 0, 0));
            assertFalse(repository.vacuumIntoBackup(null));
            assertEquals(1, repository.findLive(ID).orElseThrow().getSchemaVersion());
            assertEquals(1,repository.tombstone(ID));
            try (var connection=java.sql.DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("filters.db"));
                 var statement=connection.createStatement()) {
                statement.execute("CREATE TRIGGER reject_writes BEFORE INSERT ON vehicles BEGIN SELECT RAISE(IGNORE); END");
            }
            assertFalse(repository.saveLive(snapshot(PAYLOAD)), "A database-side rejection must not report persistence");
            assertTrue(repository.find(ID).orElseThrow().isDeleted());
            assertEquals(1,repository.findChunk("world",0,0).size(), "Only the unrelated stored row should remain live");
        } finally { repository.close(); }
    }

    @Test void chunkIndexClearsDeletedAndInvalidLocationsWithoutLeakingCounts() {
        OccupiedChunkIndex index = new OccupiedChunkIndex();
        index.replace(null); index.putLive((VehicleSnapshot) null); index.putLive((OccupiedChunkIndex.LiveLocation) null);
        index.putLive(null, "world", 0, 0); index.putLive(" ", "world", 0, 0); index.putLive(ID, null, 0, 0); index.putLive(ID, " ", 0, 0);
        assertEquals(0, index.count(null, 0, 0)); assertEquals(0, index.count(" ", 0, 0));
        index.putLive(snapshot(PAYLOAD));
        index.putLive(new VehicleSnapshot(ID, "cart", "Cart", "none", "world", 0, 0, 0, 0, 0, 0, "{}", 1, 1, true, 1));
        assertFalse(index.isOccupied("world", 0, 0)); index.remove(null); index.remove(" ");
    }

    @Test void failureLoggingAndRevisionCopiesPreserveUsefulState() {
        VehicleRepository repository = mock(VehicleRepository.class);
        when(repository.findChunk("world", 0, 0)).thenThrow(new IllegalStateException("offline"));
        try (var logger = mockStatic(VFLogger.class)) {
            assertTrue(new VehiclePersistence(repository).findChunk("world", 0, 0).isEmpty());
            logger.verify(() -> VFLogger.log("SQLite chunk query failed: offline"));
        }
        VehicleSnapshot original = snapshot(PAYLOAD), next = original.withRevision(4, 123);
        assertEquals(4, next.getRevision()); assertEquals(123, next.getUpdatedAt());
        assertEquals(original.getPayloadJson(), next.getPayloadJson());
        assertEquals(original.getUuid(), next.getUuid()); assertEquals(original.getOwner(), next.getOwner());
        assertEquals(1, original.getRevision());
        assertTrue(new IncompleteComponent(Component.HULL, 1, 0, 0).hasDamage());
        assertFalse(new IncompleteComponent(Component.HULL, 0, 0, 0).hasDamage());
    }

    @Test void legacyConsistStringsAndMissingDataRemainCompatible() {
        ConsistData empty = ConsistData.fromJson(null);
        assertTrue(empty.isUnbound()); assertDoesNotThrow(() -> empty.put(null));
        ConsistData legacy = ConsistData.fromJson(VehiclePayloadCoverageTest.object(
            "{\"splineId\":\"track\",\"s\":\"12.5\",\"travelSign\":\"-1\",\"junction\":\"switch\",\"diverge\":\"true\"}"));
        assertEquals(12.5, legacy.getS()); assertEquals(-1, legacy.getTravelSign()); assertTrue(legacy.isDiverge());
        ConsistData invalid = ConsistData.fromJson(VehiclePayloadCoverageTest.object(
            "{\"splineId\":\"track\",\"s\":\"invalid\",\"travelSign\":\"invalid\"}"));
        assertNull(invalid.getS()); assertEquals(1, invalid.getTravelSign());
    }

    static VehicleSnapshot snapshot(String payload) {
        return new VehicleSnapshot(ID, "cart", "Cart", "player_Alice", "world", 0, 64, 0, 45, 0, 0, payload, 1, 1, false, 1);
    }
}
