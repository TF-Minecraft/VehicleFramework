package net.tfminecraft.vehicleframework.database;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VehicleSqliteBackup {
	public static final int KEEP = 3;
	public static final String FILE_PREFIX = "vehicles-";
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
	private static final Pattern SNAPSHOT_NAME = Pattern.compile("^" + FILE_PREFIX + "(\\d{8}-\\d{6})(?:-(\\d+))?\\.db$");

	private VehicleSqliteBackup() {
	}

	public static File backupDir(File dataFolder) {
		return new File(dataFolder, "data/backups");
	}

	public static File nextBackupFile(File backupDir) {
		backupDir.mkdirs();
		String stamp = LocalDateTime.now().format(STAMP);
		File dest = new File(backupDir, FILE_PREFIX + stamp + ".db");
		int suffix = 2;
		while (dest.exists()) {
			dest = new File(backupDir, FILE_PREFIX + stamp + "-" + suffix + ".db");
			suffix++;
		}
		return dest;
	}

	public static String vacuumIntoSql(File dest) {
		String path = dest.getAbsolutePath().replace("'", "''");
		return "VACUUM INTO '" + path + "'";
	}

	public static void rotate(File backupDir) {
		List<File> snapshots = listSnapshots(backupDir);
		snapshots.sort(Comparator.comparingLong(File::lastModified).reversed()
				.thenComparing(VehicleSqliteBackup::compareSnapshotNames));
		for (int i = KEEP; i < snapshots.size(); i++) {
			snapshots.get(i).delete();
		}
	}

	private static int compareSnapshotNames(File first, File second) {
		ParsedSnapshotName firstName = parseSnapshotName(first.getName());
		ParsedSnapshotName secondName = parseSnapshotName(second.getName());
		if (firstName == null || secondName == null) {
			if (firstName == null && secondName == null) {
				return 0;
			}
			return firstName == null ? 1 : -1;
		}
		int stampComparison = secondName.stamp().compareTo(firstName.stamp());
		if (stampComparison != 0) {
			return stampComparison;
		}
		return secondName.sequence().compareTo(firstName.sequence());
	}

	private static ParsedSnapshotName parseSnapshotName(String name) {
		Matcher matcher = SNAPSHOT_NAME.matcher(name);
		if (!matcher.matches()) {
			return null;
		}
		try {
			LocalDateTime stamp = LocalDateTime.parse(matcher.group(1), STAMP);
			BigInteger sequence = matcher.group(2) == null ? BigInteger.ONE : new BigInteger(matcher.group(2));
			return new ParsedSnapshotName(stamp, sequence);
		} catch (RuntimeException ignored) {
			return null;
		}
	}

	private record ParsedSnapshotName(LocalDateTime stamp, BigInteger sequence) {
	}

	public static List<File> listSnapshots(File backupDir) {
		File[] files = backupDir == null ? null : backupDir.listFiles();
		if (files == null) {
			return new ArrayList<>();
		}
		List<File> snapshots = new ArrayList<>();
		for (File file : files) {
			if (file.isFile() && isSnapshotName(file.getName())) {
				snapshots.add(file);
			}
		}
		return snapshots;
	}

	public static boolean isSnapshotName(String name) {
		return name != null
				&& name.startsWith(FILE_PREFIX)
				&& name.toLowerCase().endsWith(".db");
	}

	public static File findNewestValid(File backupDir) {
		List<File> snapshots = listSnapshots(backupDir);
		snapshots.sort(Comparator.comparingLong(File::lastModified).reversed()
				.thenComparing(VehicleSqliteBackup::compareSnapshotNames));
		for (File snapshot : snapshots) {
			if (isValidDatabase(snapshot)) {
				return snapshot;
			}
		}
		return null;
	}

	public static boolean isValidDatabase(File dbFile) {
		if (dbFile == null || !dbFile.isFile() || dbFile.length() == 0) {
			return false;
		}
		String url = "jdbc:sqlite:" + dbFile.toPath().toAbsolutePath().toUri().toASCIIString() + "?mode=ro";
		try (java.sql.Connection connection = java.sql.DriverManager.getConnection(url);
				java.sql.Statement statement = connection.createStatement()) {
			try (java.sql.ResultSet check = statement.executeQuery("PRAGMA quick_check")) {
				if (!check.next() || !"ok".equalsIgnoreCase(check.getString(1)) || check.next()) return false;
			}
			// Require the original vehicle schema. Later name/owner columns can be migrated on restore.
			try (java.sql.ResultSet schema = statement.executeQuery(
					"SELECT uuid,type_id,world,x,y,z,yaw,chunk_x,chunk_z,payload_json,schema_version,revision,deleted,updated_at FROM vehicles LIMIT 0")) {
				return true;
			}
		} catch (java.sql.SQLException invalid) {
			return false;
		}
	}

	public static void replaceLive(File liveFile, File backupFile) throws IOException {
		File parent = liveFile.getAbsoluteFile().getParentFile();
		Files.createDirectories(parent.toPath());
		java.nio.file.Path staged = Files.createTempFile(parent.toPath(), "vehicles-restore-", ".db");
		try (java.io.Closeable cleanup = () -> Files.deleteIfExists(staged)) {
			// Finish reading the backup before touching the live database or its journal.
			Files.copy(backupFile.toPath(), staged, StandardCopyOption.REPLACE_EXISTING);
			File original = sidelineCorrupt(liveFile);
			try {
				Files.move(staged, liveFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException installFailure) {
				if (original != null) {
					try {
						Files.move(original.toPath(), liveFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
					} catch (IOException restoreFailure) {
						installFailure.addSuppressed(restoreFailure);
					}
				}
				throw installFailure;
			}
			deleteSidecar(liveFile, "-wal");
			deleteSidecar(liveFile, "-shm");
		}
	}

	private static File sidelineCorrupt(File liveFile) throws IOException {
		if (!liveFile.exists()) {
			return null;
		}
		File corrupt = new File(
				liveFile.getParentFile(),
				liveFile.getName() + ".corrupt-" + LocalDateTime.now().format(STAMP));
		int suffix = 2;
		while (corrupt.exists()) {
			corrupt = new File(
					liveFile.getParentFile(),
					liveFile.getName() + ".corrupt-" + LocalDateTime.now().format(STAMP) + "-" + suffix);
			suffix++;
		}
		if (!liveFile.renameTo(corrupt)) {
			Files.move(liveFile.toPath(), corrupt.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
		return corrupt;
	}

	private static void deleteSidecar(File liveFile, String suffix) {
		File sidecar = new File(liveFile.getParentFile(), liveFile.getName() + suffix);
		if (sidecar.exists()) {
			sidecar.delete();
		}
	}
}
