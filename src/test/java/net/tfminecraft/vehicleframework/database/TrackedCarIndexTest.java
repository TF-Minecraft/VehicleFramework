package net.tfminecraft.vehicleframework.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
