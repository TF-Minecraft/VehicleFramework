package net.tfminecraft.vehicleframework.database;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.*;
import net.tfminecraft.tlibs.database.SqliteDatabase;
import net.tfminecraft.tlibs.database.SqliteDatabaseException;
import net.tfminecraft.vehicleframework.VFLogger;

class VehicleBackupCoverageTest {
    @TempDir Path folder;

    @Test void failedRestoreLeavesOriginalDatabaseAndItsSidecarsUntouched() throws Exception {
        Path live = folder.resolve("vehicles.db"); Files.writeString(live, "recoverable original bytes");
        Path wal = folder.resolve("vehicles.db-wal"), shm = folder.resolve("vehicles.db-shm"); Files.writeString(wal, "wal bytes"); Files.writeString(shm, "shm bytes");
        assertThrows(IOException.class, () -> VehicleSqliteBackup.replaceLive(live.toFile(), folder.resolve("missing.db").toFile()));
        assertTrue(Files.exists(live), "Failed backup reads must not remove the live database"); assertEquals("recoverable original bytes", Files.readString(live));
        assertEquals("wal bytes", Files.readString(wal)); assertEquals("shm bytes", Files.readString(shm));
    }

    @Test void backupValidationRejectsEmptyFilesWithoutInitializingThem() throws Exception {
        Path empty = Files.createFile(folder.resolve("vehicles-empty.db"));
        assertFalse(VehicleSqliteBackup.isValidDatabase(empty.toFile()), "A zero-byte file is not a usable vehicle backup"); assertEquals(0, Files.size(empty));
    }

    @Test void backupValidationRejectsOtherSqliteDatabasesWithoutCreatingVehicleTables() throws Exception {
        Path unrelated = folder.resolve("vehicles-unrelated.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + unrelated); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE unrelated(value TEXT)"); statement.execute("INSERT INTO unrelated VALUES ('preserve')");
        }
        byte[] original = Files.readAllBytes(unrelated); assertFalse(VehicleSqliteBackup.isValidDatabase(unrelated.toFile()));
        assertArrayEquals(original, Files.readAllBytes(unrelated));
    }

    @Test void restoreSelectsAnOlderRealBackupOverANewerEmptySnapshot() throws Exception {
        Path backups = Files.createDirectory(folder.resolve("backups")); File good = database(backups.resolve("vehicles-20260101-000000.db"));
        Path empty = Files.createFile(backups.resolve("vehicles-20260102-000000.db"));
        Files.setLastModifiedTime(good.toPath(), FileTime.fromMillis(1000)); Files.setLastModifiedTime(empty, FileTime.fromMillis(2000));
        assertEquals(good, VehicleSqliteBackup.findNewestValid(backups.toFile())); assertEquals(0, Files.size(empty));
    }

    @Test void newestBackupUsesSequenceNumbersWhenFilesystemTimestampsTie() throws Exception {
        Path backups = Files.createDirectory(folder.resolve("backups")); File old = database(backups.resolve("vehicles-20260101-000000.db"));
        File newest = backups.resolve("vehicles-20260101-000000-10.db").toFile(); Files.copy(old.toPath(), newest.toPath());
        Files.setLastModifiedTime(old.toPath(), FileTime.fromMillis(1000)); Files.setLastModifiedTime(newest.toPath(), FileTime.fromMillis(1000));
        File directory = spy(backups.toFile()); doReturn(new File[]{old, newest}).when(directory).listFiles();
        assertEquals(newest, VehicleSqliteBackup.findNewestValid(directory));
    }

    @Test void failedSidelineNeverDeletesTheOriginalDatabase() throws Exception {
        Path live = folder.resolve("vehicles.db"); Files.writeString(live, "original"); File backup = database(folder.resolve("backup.db"));
        File source = spy(live.toFile()); doReturn(false).when(source).renameTo(any(File.class));
        try (var files = mockStatic(Files.class, call -> {
            if (call.getMethod().getName().equals("move") && live.equals(call.getArgument(0))) throw new IOException("simulated move failure");
            return call.callRealMethod();
        })) {
            assertThrows(IOException.class, () -> VehicleSqliteBackup.replaceLive(source, backup));
        }
        assertEquals("original", Files.readString(live));
    }

    @Test void failedFinalInstallationRestoresTheOriginalDatabaseAndRetainsItsJournals() throws Exception {
        Path live = Files.writeString(folder.resolve("vehicles.db"), "original recoverable bytes"); File backup = database(folder.resolve("backup.db"));
        Path wal = Files.writeString(folder.resolve("vehicles.db-wal"), "original wal"), shm = Files.writeString(folder.resolve("vehicles.db-shm"), "original shm");
        try (var files = mockStatic(Files.class, call -> {
            if (call.getMethod().getName().equals("move") && ((Path)call.getArgument(0)).getFileName().toString().startsWith("vehicles-restore-"))
                throw new IOException("simulated final install failure");
            return call.callRealMethod();
        })) {
            IOException failure = assertThrows(IOException.class, () -> VehicleSqliteBackup.replaceLive(live.toFile(), backup));
            assertEquals("simulated final install failure", failure.getMessage());
        }
        assertTrue(Files.exists(live), "A failed final rename must restore the original live file"); assertEquals("original recoverable bytes", Files.readString(live));
        assertEquals("original wal", Files.readString(wal)); assertEquals("original shm", Files.readString(shm));
        try (Stream<Path> files = Files.list(folder)) { assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith("vehicles-restore-"))); }
    }

    @Test void refusedLegacyRenameCanFallBackToNioMoveWithoutLosingTheOriginal() throws Exception {
        Path live = Files.writeString(folder.resolve("vehicles.db"), "original"); File backup = database(folder.resolve("backup.db"));
        File source = spy(live.toFile()); doReturn(false).when(source).renameTo(any(File.class));
        VehicleSqliteBackup.replaceLive(source, backup); assertTrue(VehicleSqliteBackup.isValidDatabase(live.toFile()));
        try (Stream<Path> files = Files.list(folder)) {
            assertTrue(files.filter(path -> path.getFileName().toString().startsWith("vehicles.db.corrupt-")).anyMatch(path -> read(path).equals("original")));
        }
    }

    @Test void readonlyValidationPreservesLegacySchemaUntilAnActualRestoreMigratesIt() throws Exception {
        Path backups = Files.createDirectory(folder.resolve("backups")); Path old = backups.resolve("vehicles-20250101-000000.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + old); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE vehicles(uuid TEXT PRIMARY KEY,type_id TEXT,world TEXT,x REAL,y REAL,z REAL,yaw REAL,chunk_x INTEGER,chunk_z INTEGER,payload_json TEXT,schema_version INTEGER,revision INTEGER,deleted INTEGER,updated_at INTEGER)");
            statement.execute("INSERT INTO vehicles VALUES('legacy','tug','world',1,64,1,0,0,0,'{}',1,1,0,1)");
        }
        byte[] original = Files.readAllBytes(old); assertTrue(VehicleSqliteBackup.isValidDatabase(old.toFile())); assertArrayEquals(original, Files.readAllBytes(old));
        Path live = Files.writeString(folder.resolve("vehicles.db"), "corrupt");
        try (var logger = mockStatic(VFLogger.class)) {
            VehicleRepository restored = VehicleRepository.openWithRestore(live.toFile(), backups.toFile());
            try { assertEquals("", restored.findLive("legacy").orElseThrow().getName()); assertEquals("none", restored.findLive("legacy").orElseThrow().getOwner()); }
            finally { restored.close(); }
        }
        assertArrayEquals(original, Files.readAllBytes(old));
    }

    @Test void validationRejectsJdbcResourceFailuresAndClosesTheRemainingResources() throws Exception {
        File backup = database(folder.resolve("backup.db")); Connection connection = mock(Connection.class); Statement statement = mock(Statement.class);
        ResultSet result = mock(ResultSet.class); when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("PRAGMA quick_check")).thenReturn(result); when(result.next()).thenReturn(true, false); when(result.getString(1)).thenReturn("ok");
        doThrow(new SQLException("failed to close integrity result")).when(result).close();
        try (var driver = mockStatic(DriverManager.class)) {
            driver.when(() -> DriverManager.getConnection(anyString())).thenReturn(connection);
            assertFalse(VehicleSqliteBackup.isValidDatabase(backup));
        }
        verify(result).close(); verify(statement).close(); verify(connection).close(); assertTrue(VehicleSqliteBackup.isValidDatabase(backup));
    }

    @Test void integrityErrorRowsRejectABackupAndCloseEveryJdbcResource() throws Exception {
        File backup = database(folder.resolve("backup.db")); Connection connection = mock(Connection.class); Statement statement = mock(Statement.class);
        ResultSet result = mock(ResultSet.class); when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("PRAGMA quick_check")).thenReturn(result); when(result.next()).thenReturn(true, false); when(result.getString(1)).thenReturn("page 5: invalid index");
        try (var driver = mockStatic(DriverManager.class)) {
            driver.when(() -> DriverManager.getConnection(anyString())).thenReturn(connection); assertFalse(VehicleSqliteBackup.isValidDatabase(backup));
        }
        verify(result).close(); verify(statement).close(); verify(connection).close();
        verify(statement, times(1)).executeQuery(anyString());
    }

    @Test void rollbackFailurePreservesTheArchivedOriginalAndReportsBothIoFailures() throws Exception {
        Path live = Files.writeString(folder.resolve("vehicles.db"), "original"); File backup = database(folder.resolve("backup.db"));
        Path wal = Files.writeString(folder.resolve("vehicles.db-wal"), "wal");
        try (var files = mockStatic(Files.class, call -> {
            if (call.getMethod().getName().equals("move")) {
                String name = ((Path)call.getArgument(0)).getFileName().toString();
                if (name.startsWith("vehicles-restore-")) throw new IOException("install failed");
                if (name.startsWith("vehicles.db.corrupt-")) throw new IOException("rollback failed");
            }
            return call.callRealMethod();
        })) {
            IOException failure = assertThrows(IOException.class, () -> VehicleSqliteBackup.replaceLive(live.toFile(), backup));
            assertEquals("install failed", failure.getMessage()); assertEquals(1, failure.getSuppressed().length);
            assertEquals("rollback failed", failure.getSuppressed()[0].getMessage());
        }
        assertEquals("wal", Files.readString(wal));
        try (Stream<Path> files = Files.list(folder)) {
            assertTrue(files.filter(path -> path.getFileName().toString().startsWith("vehicles.db.corrupt-")).anyMatch(path -> read(path).equals("original")));
        }
    }

    @Test void cleanupFailureCannotHideTheInstallFailureOrItsFailedRollback() throws Exception {
        Path live = Files.writeString(folder.resolve("vehicles.db"), "original database bytes");
        File backup = database(folder.resolve("backup.db"));
        Path wal = Files.writeString(folder.resolve("vehicles.db-wal"), "original wal");
        Path shm = Files.writeString(folder.resolve("vehicles.db-shm"), "original shm");
        IOException install = new IOException("install failed"), rollback = new IOException("rollback failed"), cleanup = new IOException("cleanup failed");
        IOException reported;
        try (var files = mockStatic(Files.class, call -> {
            if (call.getMethod().getName().equals("move")) {
                String name = ((Path)call.getArgument(0)).getFileName().toString();
                if (name.startsWith("vehicles-restore-")) throw install;
                if (name.startsWith("vehicles.db.corrupt-")) throw rollback;
            }
            if (call.getMethod().getName().equals("deleteIfExists")
                    && ((Path)call.getArgument(0)).getFileName().toString().startsWith("vehicles-restore-")) throw cleanup;
            return call.callRealMethod();
        })) {
            reported = assertThrows(IOException.class, () -> VehicleSqliteBackup.replaceLive(live.toFile(), backup));
        }
        Path archived;
        try (Stream<Path> files = Files.list(folder)) {
            archived = files.filter(path -> path.getFileName().toString().startsWith("vehicles.db.corrupt-")).findFirst().orElseThrow();
        }
        assertAll(
                () -> assertSame(install, reported, "Cleanup must not replace the primary restore error"),
                () -> assertArrayEquals(new Throwable[]{rollback, cleanup}, reported.getSuppressed()),
                () -> assertEquals("original database bytes", Files.readString(archived)),
                () -> assertEquals("original wal", Files.readString(wal)),
                () -> assertEquals("original shm", Files.readString(shm)));
    }

    @Test void backupNamesEscapeSqlQuotesAndRotationKeepsOnlyNewestSnapshots() throws Exception {
        assertEquals(folder.resolve("data/backups").toFile(), VehicleSqliteBackup.backupDir(folder.toFile()));
        assertTrue(VehicleSqliteBackup.vacuumIntoSql(folder.resolve("it's.db").toFile()).contains("it''s.db"));
        assertFalse(VehicleSqliteBackup.isSnapshotName(null)); assertFalse(VehicleSqliteBackup.isSnapshotName("notes.db"));
        assertTrue(VehicleSqliteBackup.isSnapshotName("vehicles-backup.DB"));
        assertTrue(VehicleSqliteBackup.listSnapshots(null).isEmpty()); assertTrue(VehicleSqliteBackup.listSnapshots(folder.resolve("absent").toFile()).isEmpty());
        Path snapshots = Files.createDirectory(folder.resolve("snapshots")); File first = VehicleSqliteBackup.nextBackupFile(snapshots.toFile()); Files.createFile(first.toPath());
        File next = VehicleSqliteBackup.nextBackupFile(snapshots.toFile()); assertNotEquals(first, next);
        Files.createDirectory(snapshots.resolve("vehicles-directory.db")); Files.writeString(snapshots.resolve("notes.txt"), "keep");
        for (String name : List.of("vehicles-20250101-000000.db", "vehicles-20260101-000000.db", "vehicles-20260101-000000-9.db", "vehicles-20260101-000000-10.db", "vehicles-invalid.db", "vehicles-invalid2.db", "vehicles-20269999-000000.db")) {
            Path file = Files.writeString(snapshots.resolve(name), name); Files.setLastModifiedTime(file, FileTime.fromMillis(1000));
        }
        Files.setLastModifiedTime(first.toPath(), FileTime.fromMillis(0)); VehicleSqliteBackup.rotate(snapshots.toFile());
        Set<String> kept = new HashSet<>(); for (File file : VehicleSqliteBackup.listSnapshots(snapshots.toFile())) kept.add(file.getName());
        assertEquals(Set.of("vehicles-20260101-000000.db", "vehicles-20260101-000000-9.db", "vehicles-20260101-000000-10.db"), kept);
        assertEquals("keep", Files.readString(snapshots.resolve("notes.txt"))); assertTrue(Files.isDirectory(snapshots.resolve("vehicles-directory.db")));
    }

    @Test void successfulReplacementPreservesCorruptCopiesAndClearsStaleWalSidecars() throws Exception {
        File backup = database(folder.resolve("backup.db")); Path live = folder.resolve("nested/vehicles.db");
        VehicleSqliteBackup.replaceLive(live.toFile(), backup); assertTrue(VehicleSqliteBackup.isValidDatabase(live.toFile()));
        Files.writeString(live, "first corrupt bytes"); Files.writeString(live.resolveSibling("vehicles.db-wal"), "wal"); Files.writeString(live.resolveSibling("vehicles.db-shm"), "shm");
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")); Path collision = live.resolveSibling("vehicles.db.corrupt-" + stamp);
        Files.writeString(collision, "older evidence"); VehicleSqliteBackup.replaceLive(live.toFile(), backup);
        assertEquals("older evidence", Files.readString(collision));
        try (Stream<Path> files = Files.list(live.getParent())) { assertTrue(files.filter(p -> p.getFileName().toString().startsWith("vehicles.db.corrupt-")).anyMatch(p -> read(p).equals("first corrupt bytes"))); }
        assertFalse(Files.exists(live.resolveSibling("vehicles.db-wal"))); assertFalse(Files.exists(live.resolveSibling("vehicles.db-shm")));
        assertTrue(VehicleSqliteBackup.isValidDatabase(live.toFile()));
    }

    @Test void repositoryRestoreRecoversRowsAndIgnoresLoggingFailures() throws Exception {
        Path backups = Files.createDirectory(folder.resolve("backups")); File good = database(backups.resolve("vehicles-20260101-000000.db"));
        Path live = Files.writeString(folder.resolve("vehicles.db"), "corrupt");
        try (var logger = mockStatic(VFLogger.class)) {
            logger.when(() -> VFLogger.log(anyString())).thenThrow(new IllegalStateException("logger unavailable"));
            VehicleRepository restored = VehicleRepository.openWithRestore(live.toFile(), backups.toFile());
            try { assertEquals("tug", restored.findLive("vehicle-1").orElseThrow().getTypeId()); assertEquals("wal", restored.journalMode()); assertEquals(5000, restored.busyTimeoutMillis()); }
            finally { restored.close(); }
        }
        VehicleRepository normal = VehicleRepository.openWithRestore(good, folder.resolve("missing-backups").toFile()); normal.close();
    }

    @Test void failedVacuumPreservesExistingBackupsAndLiveRows() throws Exception {
        File live = database(folder.resolve("vehicles.db")); VehicleRepository repository = VehicleRepository.open(live);
        try {
            assertFalse(repository.vacuumIntoBackup(null)); File backups = Files.createDirectory(folder.resolve("backups")).toFile();
            assertTrue(repository.vacuumIntoBackup(backups)); File saved = VehicleSqliteBackup.listSnapshots(backups).get(0); byte[] original = Files.readAllBytes(saved.toPath());
            Path blocked = Files.writeString(folder.resolve("blocked-directory"), "keep"); assertThrows(RuntimeException.class, () -> repository.vacuumIntoBackup(blocked.toFile()));
            assertArrayEquals(original, Files.readAllBytes(saved.toPath())); assertTrue(repository.findLive("vehicle-1").isPresent());
            repository.checkpointWal(false); repository.checkpointWal(true);
        } finally { repository.close(); }
    }

    @Test void corruptOrMissingCandidatesCannotBeSelected() throws Exception {
        assertFalse(VehicleSqliteBackup.isValidDatabase(null)); assertFalse(VehicleSqliteBackup.isValidDatabase(folder.toFile()));
        Files.writeString(folder.resolve("vehicles-corrupt.db"), "not sqlite");
        assertNull(VehicleSqliteBackup.findNewestValid(folder.toFile()));
        Path live = Files.writeString(folder.resolve("broken-live.db"), "not sqlite");
        assertThrows(RuntimeException.class, () -> VehicleRepository.openWithRestore(live.toFile(), folder.toFile()));
    }

    @Test void failedOpenReportsIntegrityAndPreservesTheConnectionCloseFailure() throws Exception {
        Connection connection = mock(Connection.class); PreparedStatement statement = mock(PreparedStatement.class); ResultSet result = mock(ResultSet.class);
        when(connection.prepareStatement("PRAGMA quick_check")).thenReturn(statement); when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true, false); when(result.getString(1)).thenReturn("corrupt page");
        IllegalStateException closing = new IllegalStateException("close failure");
        try (var databases = mockConstruction(SqliteDatabase.class, (database, context) -> {
            when(database.getConnection()).thenReturn(connection); doThrow(closing).when(database).close();
        })) {
            SqliteDatabaseException failure = assertThrows(SqliteDatabaseException.class, () -> VehicleRepository.open(folder.resolve("invalid.db").toFile()));
            assertTrue(failure.getMessage().contains("integrity check failed")); assertArrayEquals(new Throwable[]{closing}, failure.getSuppressed());
            verify(databases.constructed().get(0)).close(); verify(statement).close(); verify(result).close();
        }
    }

    @Test void databaseQueryFailuresAreReportedAndFailedConnectionsClose() throws Exception {
        Connection connection = mock(Connection.class); when(connection.prepareStatement("PRAGMA quick_check")).thenThrow(new SQLException("storage read failure"));
        try (var databases = mockConstruction(SqliteDatabase.class, (database, context) -> when(database.getConnection()).thenReturn(connection))) {
            SqliteDatabaseException failure = assertThrows(SqliteDatabaseException.class, () -> VehicleRepository.open(folder.resolve("failed.db").toFile()));
            assertTrue(failure.getMessage().contains("PRAGMA quick_check")); assertEquals("storage read failure", failure.getCause().getMessage());
            verify(databases.constructed().get(0)).close();
        }
    }

    @Test void restoreWriteFailureKeepsTheOriginalFailureAndDatabaseBytes() throws Exception {
        Path backups = Files.createDirectory(folder.resolve("backups")); database(backups.resolve("vehicles-20260101-000000.db"));
        Path restricted = Files.createDirectory(folder.resolve("read-only")); Path live = Files.writeString(restricted.resolve("vehicles.db"), "corrupt but preserved");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.getFileStore(restricted).supportsFileAttributeView("posix"),
                "Requires POSIX directory permissions");
        Set<java.nio.file.attribute.PosixFilePermission> original = Files.getPosixFilePermissions(restricted);
        Files.setPosixFilePermissions(restricted, java.nio.file.attribute.PosixFilePermissions.fromString("r-x------"));
        try {
            org.junit.jupiter.api.Assumptions.assumeFalse(Files.isWritable(restricted),
                    "Requires enforced write permissions (not a privileged process)");
            RuntimeException failure = assertThrows(RuntimeException.class, () -> VehicleRepository.openWithRestore(live.toFile(), backups.toFile()));
            assertTrue(failure.getSuppressed().length > 0); assertEquals("corrupt but preserved", Files.readString(live));
        } finally { Files.setPosixFilePermissions(restricted, original); }
    }

    private File database(Path path) {
        VehicleRepository repository = VehicleRepository.open(path.toFile());
        try { repository.saveLive(new VehicleSnapshot("vehicle-1", "tug", "world", 1, 64, 1, 0, 0, 0,
                "{\"id\":\"tug\",\"components\":{}}", VehicleRepository.SCHEMA_VERSION, 1, false, 1)); }
        finally { repository.close(); }
        return path.toFile();
    }
    private String read(Path path) { try { return Files.readString(path); } catch (IOException e) { throw new UncheckedIOException(e); } }
}
