package net.tfminecraft.vehicleframework.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.withSettings;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.tfminecraft.tlibs.database.SqliteDatabase;

class TrackedCarIndexTest {
	private static final String SPLINE = "11111111-2222-3333-4444-555555555555";

	@TempDir Path tempDir;

	@Test
	void parseKeepsOnlyCarsOnTrack() {
		assertNull(TrackedCarIndex.parse("a", snapshot("a", null, false)));
		assertNull(TrackedCarIndex.parse("a", snapshot("a", "{\"id\":\"horse_cart\"}", false)));
		assertNull(TrackedCarIndex.parse("a", snapshot("a", "{\"splineId\":", false)));
		assertNull(TrackedCarIndex.parse("a", snapshot("a", "[\"\\\"splineId\\\"\"]", false)));
		assertNull(TrackedCarIndex.parse("a", snapshot("a", "{\"splineId\":\"" + SPLINE + "\"}", false)));
		assertEquals(car("a", 12.5, "loco"), TrackedCarIndex.parse("a", snapshot("a", payload(12.5, "LOCO"), false)));
		assertEquals(car("a", 3, null), TrackedCarIndex.parse("a", snapshot("a", payload(3, null), false)));
	}

	@Test
	void indexFollowsSavesDeletesAndReplacement() {
		TrackedCarIndex index = new TrackedCarIndex();
		index.put(snapshot("CAR-1", payload(4, null), false));
		index.put(snapshot(" ", payload(4, null), false));
		assertEquals(List.of(car("car-1", 4, null)), index.all());
		index.put(snapshot("car-1", "{}", false));
		assertTrue(index.all().isEmpty());
		index.put(snapshot("car-1", payload(4, null), false));
		index.put(snapshot("car-1", payload(4, null), true));
		assertTrue(index.all().isEmpty());
		index.put(snapshot("car-1", payload(4, null), false));
		index.remove(null);
		index.remove("CAR-1");
		assertTrue(index.all().isEmpty());
		index.put(snapshot("car-1", payload(4, null), false));
		index.replace(List.of(snapshot("car-2", payload(9, null), false)));
		assertEquals(List.of(car("car-2", 9, null)), index.all());
		assertNull(TrackedCarIndex.normalizeUuid(null));
	}

	@Test
	void repositoryTracksCarsAcrossSavesTombstonesAndReopen() {
		java.io.File file = tempDir.resolve("vehicles.db").toFile();
		VehicleRepository repository = VehicleRepository.open(file);
		try {
			assertTrue(repository.saveLive(snapshot("car-1", payload(7, null), false)));
			repository.upsert(snapshot("car-2", payload(8, "car-1"), false));
			assertEquals(2, repository.trackedCars().size());
			assertEquals(1, repository.tombstone("car-2"));
			assertEquals(List.of(car("car-1", 7, null)), repository.trackedCars());
		} finally {
			repository.close();
		}
		VehicleRepository reopened = VehicleRepository.open(file);
		try {
			assertEquals(List.of(car("car-1", 7, null)), reopened.trackedCars());
			assertEquals(1, reopened.tombstone("car-1", 99, System.currentTimeMillis()));
			assertTrue(reopened.trackedCars().isEmpty());
		} finally {
			reopened.close();
		}
	}

	@Test
	void rolledBackChangesAreUndoneInTheIndex() {
		VehicleRepository repository = VehicleRepository.open(tempDir.resolve("rollback.db").toFile());
		try {
			assertTrue(repository.saveLive(snapshot("car-1", payload(7, null), false)));
			assertThrows(IllegalStateException.class, () -> repository.runInTransaction(() -> {
				repository.tombstone("car-1");
				repository.upsert(snapshot("car-2", payload(9, null), false));
				throw new IllegalStateException("rollback");
			}));
			assertEquals(List.of(car("car-1", 7, null)), repository.trackedCars());
		} finally {
			repository.close();
		}
		// When the indexes cannot be rebuilt either, both failures are kept with the original.
		VehicleRepository closing = VehicleRepository.open(tempDir.resolve("closing.db").toFile());
		RuntimeException failure = assertThrows(RuntimeException.class, () -> closing.runInTransaction(() -> {
			closing.close();
			throw new IllegalStateException("rollback");
		}));
		assertEquals(2, failure.getSuppressed().length);
	}

	@Test
	void errorRollbackRestoresBothIndexesAndRowsAcrossReopen() {
		java.io.File file = tempDir.resolve("error-rollback.db").toFile();
		SqliteDatabase sqlite = new SqliteDatabase(file);
		// Model the runtime provider's Error rollback contract with the older compile pin.
		try (var databases = mockConstruction(SqliteDatabase.class,
				withSettings().defaultAnswer(delegatesTo(sqlite)), (database, context) -> {
			doAnswer(invocation -> {
				Connection connection = sqlite.getConnection();
				connection.setAutoCommit(false);
				try {
					invocation.<Consumer<Connection>>getArgument(0).accept(connection);
					connection.commit();
				} catch (RuntimeException | Error failure) {
					connection.rollback();
					throw failure;
				} finally {
					connection.setAutoCommit(true);
				}
				return null;
			}).when(database).runTransaction(any());
		})) {
			VehicleRepository repository = VehicleRepository.open(file);
			try {
				assertTrue(repository.saveLive(snapshot("car-1", payload(7, null), false)));
				Error failure = new AssertionError("rollback");
				assertSame(failure, assertThrows(Error.class, () -> repository.runInTransaction(() -> {
					repository.tombstone("car-1");
					repository.saveLive(new VehicleSnapshot("car-2", "wagon", "world", 65.5, 64, 2.5,
							0f, 4, 0, payload(9, null), VehicleRepository.SCHEMA_VERSION, 1, false,
							System.currentTimeMillis()));
					throw failure;
				})));
				assertErrorRollbackState(repository);
			} finally {
				repository.close();
			}
		} finally {
			sqlite.close();
		}
		VehicleRepository reopened = VehicleRepository.open(file);
		try {
			assertErrorRollbackState(reopened);
		} finally {
			reopened.close();
		}
	}

	private static void assertErrorRollbackState(VehicleRepository repository) {
		assertTrue(repository.findLive("car-1").isPresent());
		assertTrue(repository.findLive("car-2").isEmpty());
		assertTrue(repository.hasLiveInChunk("world", 0, 0));
		assertFalse(repository.hasLiveInChunk("world", 4, 0));
		assertEquals(List.of(car("car-1", 7, null)), repository.trackedCars());
	}

	private static TrackedCar car(String uuid, double s, String parent) {
		return new TrackedCar(uuid, SPLINE, s, parent, "world", 1.5, 64, 2.5);
	}

	private static String payload(double s, String parent) {
		return "{\"id\":\"wagon\",\"splineId\":\"" + SPLINE + "\",\"s\":" + s
				+ (parent == null ? "" : ",\"parent\":\"" + parent + "\"") + "}";
	}

	private static VehicleSnapshot snapshot(String uuid, String payload, boolean deleted) {
		return new VehicleSnapshot(uuid, "wagon", "world", 1.5, 64, 2.5, 0f, 0, 0, payload,
				VehicleRepository.SCHEMA_VERSION, 1, deleted, System.currentTimeMillis());
	}
}
