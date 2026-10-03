package net.tfminecraft.vehicleframework.tracks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrackRegistrySampleTest {

	@Test
	void sampleTrack_straightIncludesBothEnds(@TempDir java.nio.file.Path dir) {
		TrackRegistry registry = new TrackRegistry(dir.toFile());
		TrackSpline spline = registry.replace(TrackSpline.fromPoints(
				UUID.randomUUID(), "world", false,
				List.of(new double[] {0, 64, 0}, new double[] {0, 64, 100})));
		List<TrackSamplePoint> points = registry.sampleTrack("world", 10);
		assertEquals(11, points.size());
		assertEquals(new TrackSamplePoint(spline.getId(), 0, 64, 0), points.getFirst());
		assertEquals(new TrackSamplePoint(spline.getId(), 0, 64, 100), points.getLast());
		for (int i = 0; i < points.size(); i++) {
			assertEquals(spline.getId(), points.get(i).splineId());
			assertEquals(i * 10, points.get(i).z(), 1e-9);
			if (i > 0) {
				assertTrue(distance(points.get(i - 1), points.get(i)) <= 10 + 1e-9);
			}
		}
	}

	@Test
	void sampleTrack_includesEverySplineInWorld(@TempDir java.nio.file.Path dir) {
		TrackRegistry registry = new TrackRegistry(dir.toFile());
		TrackSpline stem = registry.replace(TrackSpline.fromPoints(
				UUID.randomUUID(), "world", false,
				List.of(new double[] {0, 64, 0}, new double[] {0, 64, 100})));
		TrackSpline branch = registry.replace(TrackSpline.fromPoints(
				UUID.randomUUID(), "world", false,
				List.of(new double[] {0, 64, 50}, new double[] {20, 64, 50})));
		registry.replace(TrackSpline.fromPoints(
				UUID.randomUUID(), "other", false,
				List.of(new double[] {0, 64, 0}, new double[] {0, 64, 100})));
		List<TrackSamplePoint> points = registry.sampleTrack("world", 10);
		assertEquals(14, points.size());
		assertEquals(Set.of(stem.getId(), branch.getId()),
				points.stream().map(TrackSamplePoint::splineId).collect(Collectors.toSet()));
		assertTrue(points.contains(new TrackSamplePoint(branch.getId(), 20, 64, 50)));
	}

	@Test
	void sampleTrack_spacingLongerThanSplineIncludesBothEnds(@TempDir java.nio.file.Path dir) {
		TrackRegistry registry = new TrackRegistry(dir.toFile());
		TrackSpline spline = registry.replace(TrackSpline.fromPoints(
				UUID.randomUUID(), "world", false,
				List.of(new double[] {1, 64, 2}, new double[] {1, 64, 12})));
		assertEquals(List.of(
				new TrackSamplePoint(spline.getId(), 1, 64, 2),
				new TrackSamplePoint(spline.getId(), 1, 64, 12)), registry.sampleTrack("world", 20));
	}

	@Test
	void sampleTrack_includesEndBetweenSteps(@TempDir java.nio.file.Path dir) {
		TrackRegistry registry = new TrackRegistry(dir.toFile());
		registry.replace(TrackSpline.fromPoints(
				UUID.randomUUID(), "world", false,
				List.of(new double[] {0, 64, 0}, new double[] {0, 64, 25})));
		assertEquals(List.of(0.0, 10.0, 20.0, 25.0),
				registry.sampleTrack("world", 10).stream().map(TrackSamplePoint::z).toList());
	}

	@Test
	void sampleTrack_walksArcLengthAroundBend(@TempDir java.nio.file.Path dir) {
		TrackRegistry registry = new TrackRegistry(dir.toFile());
		TrackSpline spline = registry.replace(TrackSpline.fromPoints(
				UUID.randomUUID(), "world", false,
				List.of(new double[] {0, 64, 0}, new double[] {0, 70, 8}, new double[] {10, 70, 8})));
		assertEquals(List.of(
				new TrackSamplePoint(spline.getId(), 0, 64, 0),
				new TrackSamplePoint(spline.getId(), 0, 67, 4),
				new TrackSamplePoint(spline.getId(), 0, 70, 8),
				new TrackSamplePoint(spline.getId(), 5, 70, 8),
				new TrackSamplePoint(spline.getId(), 10, 70, 8)), registry.sampleTrack("world", 5));
	}

	@Test
	void sampleTrack_loopOmitsDuplicateEnd(@TempDir java.nio.file.Path dir) {
		TrackRegistry registry = new TrackRegistry(dir.toFile());
		registry.replace(TrackSpline.fromPoints(
				UUID.randomUUID(), "world", true,
				List.of(new double[] {0, 64, 0}, new double[] {10, 64, 0},
						new double[] {10, 64, 10}, new double[] {0, 64, 10})));
		for (double spacing : new double[] {10, 6}) {
			List<TrackSamplePoint> points = registry.sampleTrack("world", spacing);
			assertEquals((int) Math.ceil(40 / spacing), points.size());
			assertTrue(distance(points.getFirst(), points.getLast()) > 1e-9);
			assertEquals(1, points.stream().filter(points.getFirst()::equals).count());
		}
		assertEquals(4, registry.sampleTrack("world", 6).getLast().z(), 1e-9);
	}

	@Test
	void sampleTrack_nullOrBlankWorldIsEmpty(@TempDir java.nio.file.Path dir) {
		TrackRegistry registry = new TrackRegistry(dir.toFile());
		for (String world : new String[] {"", " \t"}) {
			registry.replace(TrackSpline.fromPoints(UUID.randomUUID(), world, false,
					List.of(new double[] {0, 64, 0}, new double[] {0, 64, 10})));
			assertTrue(registry.sampleTrack(world, 4).isEmpty());
		}
		assertTrue(registry.sampleTrack(null, 4).isEmpty());
		assertTrue(registry.sampleTrack("missing", 4).isEmpty());
	}

	@Test
	void sampleTrack_nonPositiveSpacingDefaultsToFour(@TempDir java.nio.file.Path dir) {
		TrackRegistry registry = new TrackRegistry(dir.toFile());
		registry.replace(TrackSpline.fromPoints(
				UUID.randomUUID(), "world", false,
				List.of(new double[] {0, 64, 0}, new double[] {0, 64, 10})));
		for (double spacing : new double[] {0, -10}) {
			assertEquals(List.of(0.0, 4.0, 8.0, 10.0),
					registry.sampleTrack("world", spacing).stream().map(TrackSamplePoint::z).toList());
		}
	}

	@Test
	void sampleTrack_smallSpacingIsRaisedToHalfBlock(@TempDir java.nio.file.Path dir) {
		TrackRegistry registry = new TrackRegistry(dir.toFile());
		registry.replace(TrackSpline.fromPoints(
				UUID.randomUUID(), "world", false,
				List.of(new double[] {0, 64, 0}, new double[] {0, 64, 10})));
		List<TrackSamplePoint> points = registry.sampleTrack("world", 0.1);
		assertEquals(21, points.size());
		for (int i = 0; i < points.size(); i++) {
			for (int j = i + 1; j < points.size(); j++) {
				assertTrue(distance(points.get(i), points.get(j)) >= 1e-6);
			}
		}
	}

	@Test
	void sampleTrack_spacingNearExactMultipleDoesNotDuplicateEnd(@TempDir java.nio.file.Path dir) {
		TrackRegistry registry = new TrackRegistry(dir.toFile());
		registry.replace(TrackSpline.fromPoints(
				UUID.randomUUID(), "world", false,
				List.of(new double[] {0, 64, 0}, new double[] {0, 64, 7})));
		assertEquals(11, registry.sampleTrack("world", 0.7).size());
	}

	@Test
	void sampleTrack_nanSpacingDefaultsToFour(@TempDir java.nio.file.Path dir) {
		TrackRegistry registry = new TrackRegistry(dir.toFile());
		registry.replace(TrackSpline.fromPoints(
				UUID.randomUUID(), "world", false,
				List.of(new double[] {0, 64, 0}, new double[] {0, 64, 10})));
		assertEquals(List.of(0.0, 4.0, 8.0, 10.0),
				registry.sampleTrack("world", Double.NaN).stream().map(TrackSamplePoint::z).toList());
	}

	private static double distance(TrackSamplePoint a, TrackSamplePoint b) {
		double dx = b.x() - a.x();
		double dy = b.y() - a.y();
		double dz = b.z() - a.z();
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}
}
