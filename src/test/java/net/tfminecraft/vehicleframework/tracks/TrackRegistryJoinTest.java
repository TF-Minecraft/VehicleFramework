package net.tfminecraft.vehicleframework.tracks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.tfminecraft.vehicleframework.cache.Cache;

class TrackRegistryJoinTest {

	@Test
	void appendKeepsId(@TempDir java.nio.file.Path dir) throws Exception {
		TrackRegistry registry = new TrackRegistry(dir.toFile());
		TrackSpline first = registry.lay("world", 0, 64, 0, 0, 64, 10).spline();
		UUID id = first.getId();
		TrackSpline second = registry.lay("world", 0, 64, 10, 0, 64, 20).spline();
		assertEquals(id, second.getId());
		assertTrue(second.length() > first.length());
		assertEquals(1, registry.inWorld("world").size());
	}

	@Test
	void extendingAlongRow_settlesOnRowInsteadOfWaving(@TempDir java.nio.file.Path dir) throws Exception {
		TrackRegistry registry = new TrackRegistry(dir.toFile());
		// First piece arrives at the row 5 degrees off, as on the Main line.
		double rad = Math.toRadians(95);
		registry.lay("world", 20.5 + 20 * Math.sin(rad), 64, 0.5 - 20 * Math.cos(rad), 20.5, 64, 0.5);
		double x = 20.5;
		TrackSpline spline = null;
		for (int d : new int[] {12, 9, 14, 11, 13}) {
			spline = registry.lay("world", x, 64, 0.5, x - d, 64, 0.5).spline();
			x -= d;
		}
		assertEquals(90f, spline.last().yaw, 0.05f);
		for (TrackSample sample : spline.getSamples()) {
			if (sample.x < 20.5 - 12) {
				assertEquals(0.5, sample.z, 1e-6);
			}
		}
	}

	@Test
	void extendingAlongRow_largeErrorShrinksEachClick(@TempDir java.nio.file.Path dir) throws Exception {
		double maxTurn = Cache.trackMaxTurnDegrees;
		Cache.trackMaxTurnDegrees = 35;
		try {
			largeErrorShrinks(dir);
		} finally {
			Cache.trackMaxTurnDegrees = maxTurn;
		}
	}

	private static void largeErrorShrinks(java.nio.file.Path dir) throws Exception {
		TrackRegistry registry = new TrackRegistry(dir.toFile());
		// Lab replay of Main: the rail reached the row 14 degrees off, and short
		// clicks along it kept the full error, flipping side every click.
		double rad = Math.toRadians(104);
		registry.lay("world", 20.5 + 20 * Math.sin(rad), 64, 0.5 - 20 * Math.cos(rad), 20.5, 64, 0.5);
		double x = 20.5;
		double error = 14;
		TrackSpline spline = null;
		for (int d : new int[] {8, 13, 12, 12, 13}) {
			spline = registry.lay("world", x, 64, 0.5, x - d, 64, 0.5).spline();
			x -= d;
			int n = spline.getSamples().size();
			double next = Math.abs(TrackCurve.endYaw(
					spline.xyz().get(n - 3), spline.xyz().get(n - 2), spline.xyz().get(n - 1)) - 90);
			assertTrue(next < error || next < 0.05, "error " + next + " after " + error);
			error = next;
		}
		assertEquals(0, error, 0.05);
	}

	@Test
	void extendingFromEndWithRepeatedPoint_keepsHeading(@TempDir java.nio.file.Path dir) throws Exception {
		TrackRegistry registry = new TrackRegistry(dir.toFile());
		registry.replace(TrackSpline.fromPoints(UUID.randomUUID(), "world", false, List.of(
				new double[] {0.5, 64, 0.5},
				new double[] {5.5, 64, 0.5},
				new double[] {10.5, 64, 0.5},
				new double[] {10.5, 64, 0.5})));
		TrackSpline extended = registry.lay("world", 10.5, 64, 0.5, 22.5, 64, 0.5).spline();
		for (TrackSample sample : extended.getSamples()) {
			assertEquals(0.5, sample.z, 1e-6);
		}
	}

	@Test
	void prependKeepsId(@TempDir java.nio.file.Path dir) throws Exception {
		TrackRegistry registry = new TrackRegistry(dir.toFile());
		TrackSpline first = registry.lay("world", 0, 64, 0, 0, 64, 10).spline();
		UUID id = first.getId();
		TrackSpline second = registry.lay("world", 0, 64, 0, 0, 64, -10).spline();
		assertEquals(id, second.getId());
		assertEquals(0, second.first().x, 0.2);
		assertTrue(second.first().z < -8);
	}

	@Test
	void interiorDigSplits(@TempDir java.nio.file.Path dir) throws Exception {
		TrackRegistry registry = new TrackRegistry(dir.toFile());
		TrackSpline spline = registry.lay("world", 0, 64, 0, 0, 64, 20).spline();
		UUID id = spline.getId();
		int mid = spline.getSamples().size() / 2;
		DigResult result = registry.digAt(spline, mid);
		assertEquals(DigResult.Kind.SPLIT, result.kind);
		assertEquals(id, result.kept.getId());
		assertNotEquals(id, result.tail.getId());
		assertEquals(2, registry.inWorld("world").size());
	}

	@Test
	void connectEndToStart_merges(@TempDir java.nio.file.Path dir) throws Exception {
		TrackRegistry registry = new TrackRegistry(dir.toFile());
		TrackSpline a = registry.lay("world", 0, 64, 0, 0, 64, 10).spline();
		TrackSpline b = registry.lay("world", 0, 64, 30, 0, 64, 40).spline();
		UUID keep = a.getId();
		TrackLayResult linkedResult = registry.lay("world", 0, 64, 10, 0, 64, 30);
		TrackSpline linked = linkedResult.spline();
		assertEquals(TrackLayResult.Kind.CONNECT, linkedResult.kind);
		assertEquals(keep, linked.getId());
		assertEquals(1, registry.inWorld("world").size());
		assertTrue(linked.last().z > 38);
	}

	@Test
	void replace_promotesLoopWhenEndsMeet(@TempDir java.nio.file.Path dir) {
		TrackRegistry registry = new TrackRegistry(dir.toFile());
		UUID id = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
		TrackSpline open = TrackSpline.fromPoints(
				id, "world", false,
				List.of(
						new double[] {0, 64, 0},
						new double[] {8, 64, 0},
						new double[] {8, 64, 8},
						new double[] {0.4, 64, 0}));
		TrackSpline stored = registry.replace(open);
		assertTrue(stored.isLoop());
		assertTrue(registry.get(id).orElseThrow().isLoop());
	}

	@Test
	void extendMerge_setsLoopWhenEndsMeet() {
		List<double[]> merged = List.of(
				new double[] {0, 64, 0},
				new double[] {8, 64, 0},
				new double[] {8, 64, 8},
				new double[] {0.4, 64, 0});
		assertTrue(TrackSpline.shouldLoop(merged, 1.5));
		TrackSpline next = TrackSpline.fromPoints(
				UUID.randomUUID(), "world", TrackSpline.shouldLoop(merged, 1.5), merged);
		assertTrue(next.isLoop());
	}
}
