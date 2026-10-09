package net.tfminecraft.vehicleframework.tracks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrainPathTest {
	@TempDir Path directory;
	private UUID stem;
	private UUID branch;

	@Test
	void traceFollowsAThrownSwitchOntoItsBranch() {
		TrackRegistry registry = network();
		registry.setThrown(registry.junctionsOn(stem).get(0).id, true);
		List<TrainRoute.Piece> pieces = new TrainRoute(registry, Map.of())
				.trace(new TrainRoute.Position(stem, 40, 1), 20, true, true);
		assertEquals(2, pieces.size());
		assertPiece(pieces.get(0), stem, 40, 1, 10, 0);
		assertPiece(pieces.get(1), branch, 0, 1, 10, 10);
	}

	@Test
	void traceRunsBackwardsAndStopsAtTheTrackEnd() {
		TrackRegistry registry = network();
		TrainRoute route = new TrainRoute(registry, Map.of());
		List<TrainRoute.Piece> back = route.trace(new TrainRoute.Position(stem, 40, 1), -20, false, false);
		assertEquals(1, back.size());
		assertPiece(back.get(0), stem, 40, -1, 20, 0);
		List<TrainRoute.Piece> end = route.trace(new TrainRoute.Position(stem, 90, 1), 30, true, true);
		assertEquals(1, end.size());
		assertPiece(end.get(0), stem, 90, 1, 10, 0);
	}

	@Test
	void traceStopsAtBrokenRail() {
		TrackRegistry registry = network();
		TrackSpline track = registry.get(stem).orElseThrow();
		registry.replace(track.withSegment(80, track.segment(80).withBroken(true)));
		List<TrainRoute.Piece> pieces = new TrainRoute(registry, Map.of())
				.trace(new TrainRoute.Position(stem, 60, 1), 30, true, true);
		double covered = pieces.stream().mapToDouble(TrainRoute.Piece::length).sum();
		assertTrue(covered > 15 && covered < 25, "covered " + covered);
		assertTrue(new TrainRoute(registry, Map.of())
				.trace(new TrainRoute.Position(stem, 60, 1), 30, true, false).size() >= 1);
	}

	@Test
	void rangesClipToThePathAndFollowItsDirection() {
		UUID id = UUID.randomUUID();
		TrainPath path = new TrainPath(List.of(new TrainRoute.Piece(id, 40, -1, 20, 0, false, 100)), 5);
		assertEquals(5, path.trainLength());
		assertEquals(20, path.length());
		assertEquals(List.of(new TrainPath.Range(id, 35, 40, -1)), path.ranges(0, 5));
		assertEquals(List.of(new TrainPath.Range(id, 38, 40, -1)), path.ranges(-3, 2));
		assertTrue(path.ranges(25, 30).isEmpty());
		assertEquals(15, path.rearAfter(100));
		assertEquals(0, path.rearAfter(-1));
		assertEquals(20, new TrainPath(List.of(new TrainRoute.Piece(id, 0, 1, 20, 0, false, 100)), 50).trainLength());
	}

	@Test
	void loopRangesSplitAtTheSeam() {
		UUID id = UUID.randomUUID();
		TrainPath path = new TrainPath(List.of(new TrainRoute.Piece(id, 98, 1, 5, 0, true, 100)), 5);
		assertEquals(List.of(new TrainPath.Range(id, 98, 100, 1), new TrainPath.Range(id, 0, 3, 1)),
				path.ranges(0, 5));
		TrainPath inside = new TrainPath(List.of(new TrainRoute.Piece(id, 110, -1, 5, 0, true, 100)), 5);
		assertEquals(List.of(new TrainPath.Range(id, 5, 10, -1)), inside.ranges(0, 5));
		TrainPath degenerate = new TrainPath(List.of(new TrainRoute.Piece(id, 0, 1, 1, 0, true, 0)), 1);
		assertEquals(List.of(new TrainPath.Range(id, 0, 1, 1)), degenerate.ranges(0, 1));
	}

	@Test
	void standingCarsCoverEitherSideAndStopAtTrackEnds() {
		TrackSpline open = TrackSpline.fromPoints(UUID.randomUUID(), "world", false,
				List.of(new double[]{0, 64, 0}, new double[]{0, 64, 100}));
		TrainPath atStart = TrainPath.standing(open, 1, 3);
		assertEquals(4, atStart.trainLength(), 1e-9);
		assertEquals(List.of(new TrainPath.Range(open.getId(), 0, 4, 1)), atStart.ranges(0, 4));
		assertTrue(atStart.hasHead());
		assertEquals(4, atStart.headTo(), 1e-9);
		TrainPath atEnd = TrainPath.standing(open, open.length() - 1, -2);
		assertEquals(0, atEnd.trainLength(), 1e-9);
		// Cars whose locomotive is elsewhere have no head.
		TrainPath wagons = new TrainPath(List.of(
				new TrainRoute.Piece(open.getId(), 0, 1, 20, 0, false, open.length())), 20, 1, 0);
		assertFalse(wagons.hasHead());
		assertEquals(1, wagons.headFrom(), 1e-9);

		TrackSpline loop = TrackSpline.fromPoints(UUID.randomUUID(), "world", true,
				List.of(new double[]{0, 64, 0}, new double[]{0, 64, 50}, new double[]{50, 64, 50}));
		List<TrainPath.Range> seam = TrainPath.standing(loop, 1, 3).ranges(0, 6);
		assertEquals(2, seam.size());
		assertEquals(loop.length(), seam.get(0).hi(), 1e-9);
		assertEquals(4, seam.get(1).hi(), 1e-9);
	}

	@Test
	void overlapFindsSharedRailOnTheSameSpline() {
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		TrainPath.Range x = new TrainPath.Range(a, 0, 10, 1);
		TrainPath.Range y = new TrainPath.Range(a, 10, 20, -1);
		TrainPath.Range far = new TrainPath.Range(a, 11, 20, 1);
		TrainPath.Range other = new TrainPath.Range(b, 0, 10, 1);
		TrainPath.Range[] hit = TrainPath.overlap(List.of(x), List.of(other, y));
		assertSame(x, hit[0]);
		assertSame(y, hit[1]);
		assertNull(TrainPath.overlap(List.of(x), List.of(far, other)));
		assertNull(TrainPath.overlap(List.of(far), List.of(x.overlaps(far) ? far : other)));

		TrainPath onA = new TrainPath(List.of(new TrainRoute.Piece(a, 0, 1, 5, 0, false, 10)), 1);
		TrainPath onB = new TrainPath(List.of(new TrainRoute.Piece(b, 0, 1, 5, 0, false, 10)), 1);
		assertTrue(onA.sharesSpline(onA));
		assertFalse(onA.sharesSpline(onB));
	}

	private static void assertPiece(TrainRoute.Piece piece, UUID spline, double s, int direction,
			double length, double start) {
		assertEquals(spline, piece.splineId());
		assertEquals(s, piece.s(), 1e-9);
		assertEquals(direction, piece.direction());
		assertEquals(length, piece.length(), 1e-9);
		assertEquals(start, piece.start(), 1e-9);
	}

	private TrackRegistry network() {
		TrackStore store = new TrackStore(directory.toFile());
		stem = UUID.randomUUID();
		branch = UUID.randomUUID();
		List<double[]> points = new java.util.ArrayList<>();
		for (int z = 0; z <= 100; z++) {
			points.add(new double[]{0, 64, z});
		}
		store.save(TrackSpline.fromPoints(stem, "world", false, points));
		store.save(TrackSpline.fromPoints(branch, "world", false,
				List.of(new double[]{0, 64, 50}, new double[]{0, 64, 80})));
		store.saveJunction("world", new TrackJunction(UUID.randomUUID(), stem, 50,
				1, TrackJunction.Side.LEFT, branch, false));
		TrackRegistry registry = new TrackRegistry(directory.toFile());
		registry.loadFromDisk();
		return registry;
	}
}
