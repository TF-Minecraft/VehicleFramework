package net.tfminecraft.vehicleframework.tracks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrackRouteQueryPointsTest {

	@Test
	void lengthMatchesEveryExistingFixture() {
		TrackSpline slope = TrackSpline.fromPoints(UUID.randomUUID(), "world", false,
				List.of(new double[] {0, 0, 0}, new double[] {0, 3, 4}));
		route(List.of(slope), List.of(), "world", 0, 2, 0, 0, 4, 0, 4);

		TrackSpline stem = line("world", 0, 0, 0, 80);
		TrackSpline branch = line("world", 0, 40, 50, 40);
		TrackJunction junction = join(stem, 40, branch);
		route(List.of(stem, branch), List.of(junction), "world", 0, 10, 0, 20, 40, 0, 4);
		route(List.of(stem, branch), List.of(junction.withThrown(true).withFacing(1)),
				"world", 0, 10, 0, 20, 40, 0, 4);

		TrackSpline first = line("world", 0, 0, 100, 0);
		TrackSpline second = line("world", 100, 0, 100, 60);
		TrackSpline third = line("world", 100, 60, 140, 60);
		route(List.of(first, second, third), List.of(join(first, 100, second), join(second, 60, third)),
				"world", 0, 0, 0, 140, 60, 0, 4);

		route(List.of(line("world", 0, 0, 0, 20), line("world", 100, 0, 120, 0)),
				List.of(), "world", 0, 10, 0, 110, 0, 0, 4);
		TrackSpline loop = rectangle();
		route(List.of(loop), List.of(), "world", 0, 0, 0, 0, 10, 0, 4);
		route(List.of(loop), List.of(), "world", 0, 0, 0, 30, 0, 0, 4);
		route(List.of(samples(0, 50)), List.of(), "world", 0, 10, 2, 10, 10, 2, 4);
		route(List.of(samples(0, 10, 30)), List.of(), "world", 0, 10, 5, 0, 25, 2, 4);
		route(List.of(samples(0, 10, 30)), List.of(), "world", 0, 10, 5, 0, 14, 2, 4);

		stem = line("world", 0, 0, 80, 0);
		branch = line("world", 40, 0, 40, 50);
		route(List.of(stem, branch), List.of(join(stem, 40, null)), "world", 0, 0, 0, 40, 50, 0, 4);
		route(List.of(breakAt(samples(0, 40, 80, 120), 1)), List.of(), "world", 0, 0, 0, 0, 120, 0, 4);
		route(List.of(breakAt(samples(0, 40, 80, 120), 2)), List.of(), "world", 0, 0, 0, 0, 40, 0, 4);
		route(List.of(breakAt(rectangle(), 3)), List.of(), "world", 0, 0, 0, 0, 10, 0, 4);
		route(List.of(breakAt(rectangle(), 3, 1)), List.of(), "world", 0, 0, 0, 0, 10, 0, 4);
		stem = breakAt(samples(0, 30, 60, 90), 1);
		branch = line("world", 0, 90, 40, 90);
		route(List.of(stem, branch), List.of(join(stem, 90, branch)), "world", 0, 0, 0, 40, 90, 0, 4);
		route(List.of(breakAt(samples(0, 10, 40, 100), 0)), List.of(), "world", 0, 0, 25, 0, 100, 0, 4);

		TrackSpline alpha = line("alpha", 0, 0, 10, 0);
		route(List.of(alpha), List.of(), "beta", 0, 0, 0, 10, 0, 0, 4);
		route(List.of(alpha), List.of(), "missing", 0, 0, 0, 10, 0, 0, 4);
		stem = line("overworld", 0, 0, 30, 0);
		branch = line("nether", 15, 0, 15, 40);
		route(List.of(stem, branch), List.of(join(stem, 15, branch)), "overworld", 0, 0, 0, 15, 40, 1, 4);
	}

	@Test
	void straightSpline_pointsRunFromAToBInsideRadii() {
		TrackSpline spline = samples(0, 10, 30);
		TrackRoute route = route(List.of(spline), List.of(), "world", 0, 10, 5, 0, 25, 2, 3).orElseThrow();
		assertEquals(List.of(15.0, 18.0, 21.0, 23.0), route.points().stream().map(TrackSamplePoint::z).toList());
		assertSteps(route, 3);
	}

	@Test
	void againstSplineDirection_pointsRunFromAToB() {
		TrackSpline spline = samples(0, 10, 30);
		TrackRoute route = route(List.of(spline), List.of(), "world", 0, 25, 2, 0, 10, 5, 3).orElseThrow();
		assertEquals(List.of(23.0, 20.0, 17.0, 15.0), route.points().stream().map(TrackSamplePoint::z).toList());
		assertSteps(route, 3);
	}

	@Test
	void junction_pointsStayContinuousAndIncludeSplineExit() {
		TrackSpline stem = samples(0, 80);
		TrackSpline branch = line("world", 0, 40, 50, 40);
		TrackJunction junction = join(stem, 40, branch);
		TrackRoute forward = route(List.of(stem, branch), List.of(junction),
				"world", 0, 11, 0, 20, 40, 0, 4).orElseThrow();
		assertEquals(49, forward.length(), 1e-6);
		assertSteps(forward, 4);
		assertTrue(forward.points().contains(new TrackSamplePoint(stem.getId(), 0, 0, 40)));
		assertTrue(forward.points().contains(new TrackSamplePoint(branch.getId(), 0, 0, 40)));
		boolean onBranch = false;
		for (TrackSamplePoint point : forward.points()) {
			if (point.splineId().equals(branch.getId())) {
				onBranch = true;
				assertEquals(40, point.z(), 1e-6);
			} else {
				assertTrue(!onBranch);
				assertEquals(0, point.x(), 1e-6);
			}
		}
		TrackRoute reverse = route(List.of(stem, branch), List.of(junction),
				"world", 20, 40, 0, 0, 11, 0, 4).orElseThrow();
		assertSteps(reverse, 4);
		assertEquals(branch.getId(), reverse.points().getFirst().splineId());
		assertEquals(stem.getId(), reverse.points().getLast().splineId());
		assertTrue(reverse.points().contains(new TrackSamplePoint(branch.getId(), 0, 0, 40)));
	}

	@Test
	void loop_shortWayCrossesSeamInBothDirections() {
		TrackSpline loop = rectangle();
		TrackRoute reverse = route(List.of(loop), List.of(), "world", 2, 0, 0, 0, 8, 0, 2).orElseThrow();
		assertEquals(10, reverse.length(), 1e-6);
		assertEquals(List.of(
				point(loop, 2, 0), point(loop, 0, 0), point(loop, 0, 2),
				point(loop, 0, 4), point(loop, 0, 6), point(loop, 0, 8)), reverse.points());
		assertSteps(reverse, 2);
		TrackRoute forward = route(List.of(loop), List.of(), "world", 0, 8, 0, 2, 0, 0, 2).orElseThrow();
		assertEquals(reverse.points().reversed(), forward.points());
	}

	@Test
	void junctionsOnLoop_crossSeamBetweenAnchors() {
		for (TrackSpline loop : List.of(rectangle(), breakAt(rectangle(), 1))) {
			TrackSpline first = line("world", 2, 0, 2, -10);
			TrackSpline second = line("world", 0, 8, -10, 8);
			List<TrackSpline> splines = List.of(loop, first, second);
			List<TrackJunction> junctions = List.of(join(loop, 2, first), join(loop, 72, second));
			TrackRoute route = route(splines, junctions, "world", 2, -10, 0, -10, 8, 0, 2).orElseThrow();
			assertEquals(30, route.length(), 1e-6);
			assertEquals(List.of(
					point(loop, 2, 0), point(loop, 0, 0), point(loop, 0, 2),
					point(loop, 0, 4), point(loop, 0, 6), point(loop, 0, 8)),
					route.points().stream().filter(point -> point.splineId().equals(loop.getId())).toList());
			TrackRoute reverse = route(splines, junctions, "world", -10, 8, 0, 2, -10, 0, 2).orElseThrow();
			assertEquals(route.points().reversed(), reverse.points());
		}
	}

	@Test
	void repeatedPositionsKeepDistinctArcLengthSamples() {
		TrackSpline spline = samples(0, 4, 6, 4, 8);
		TrackRoute route = route(List.of(spline), List.of(), "world", 0, 0, 0, 0, 8, 0, 4).orElseThrow();
		assertEquals(12, route.length(), 1e-6);
		assertEquals(List.of(0.0, 4.0, 4.0, 8.0), route.points().stream().map(TrackSamplePoint::z).toList());
	}

	@Test
	void loop_brokenShortSideForcesLongWay() {
		TrackSpline loop = breakAt(rectangle(), 3);
		TrackRoute route = route(List.of(loop), List.of(), "world", 0, 0, 0, 0, 10, 0, 5).orElseThrow();
		assertEquals(70, route.length(), 1e-6);
		assertSteps(route, 5);
		assertTrue(route.points().contains(point(loop, 30, 0)));
		assertTrue(route.points().contains(point(loop, 30, 10)));
		assertTrue(route(List.of(breakAt(loop, 1)), List.of(), "world", 0, 0, 0, 0, 10, 0, 5).isEmpty());
	}

	@Test
	void brokenLoopPieceStillCrossesSeam() {
		TrackSpline loop = breakAt(rectangle(), 1);
		TrackRoute route = route(List.of(loop), List.of(), "world", 2, 0, 0, 0, 8, 0, 2).orElseThrow();
		assertEquals(10, route.length(), 1e-6);
		assertEquals(List.of(
				point(loop, 2, 0), point(loop, 0, 0), point(loop, 0, 2),
				point(loop, 0, 4), point(loop, 0, 6), point(loop, 0, 8)), route.points());
	}

	@Test
	void overlappingAreas_haveOnePointAndZeroLength() {
		TrackSpline spline = samples(0, 10, 30);
		TrackRoute route = route(List.of(spline), List.of(), "world", 0, 10, 5, 0, 14, 2, 4).orElseThrow();
		assertEquals(0, route.length());
		assertEquals(1, route.points().size());
		TrackRoute seam = route(List.of(rectangle()), List.of(), "world", 0, 0, 0, 0, 0, 0, 4).orElseThrow();
		assertEquals(0, seam.length());
		assertEquals(1, seam.points().size());
	}

	@Test
	void spacingDefaultsAndMinimumMatchSampleTrack() {
		TrackSpline spline = samples(0, 10);
		for (double spacing : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
			TrackRoute route = route(List.of(spline), List.of(), "world", 0, 0, 0, 0, 10, 0, spacing).orElseThrow();
			assertEquals(List.of(0.0, 4.0, 8.0, 10.0), route.points().stream().map(TrackSamplePoint::z).toList());
		}
		TrackRoute small = route(List.of(spline), List.of(), "world", 0, 0, 0, 0, 10, 0, 0.1).orElseThrow();
		assertEquals(21, small.points().size());
		assertSteps(small, 0.5);
		assertEquals(2, route(List.of(spline), List.of(), "world", 0, 0, 0, 0, 10, 0, 20).orElseThrow().points().size());
	}

	@Test
	void pointsUseArcLengthIncludingHeight() {
		TrackSpline spline = TrackSpline.fromPoints(UUID.randomUUID(), "world", false,
				List.of(new double[] {0, 0, 0}, new double[] {0, 6, 8}, new double[] {10, 6, 8}));
		TrackRoute route = route(List.of(spline), List.of(), "world", 0, 0, 0, 10, 8, 0, 5).orElseThrow();
		assertEquals(List.of(
				new TrackSamplePoint(spline.getId(), 0, 0, 0),
				new TrackSamplePoint(spline.getId(), 0, 3, 4),
				new TrackSamplePoint(spline.getId(), 0, 6, 8),
				new TrackSamplePoint(spline.getId(), 5, 6, 8),
				new TrackSamplePoint(spline.getId(), 10, 6, 8)), route.points());
	}

	@Test
	void recordCopiesPointsAndMakesThemUnmodifiable() {
		List<TrackSamplePoint> points = new ArrayList<>(List.of(new TrackSamplePoint(UUID.randomUUID(), 1, 2, 3)));
		TrackRoute route = new TrackRoute(0, points);
		points.clear();
		assertEquals(1, route.points().size());
		assertThrows(UnsupportedOperationException.class, () -> route.points().clear());
	}

	@Test
	void registryDelegatesWithoutChangingTrack(@TempDir java.nio.file.Path dir) {
		TrackRegistry registry = new TrackRegistry(dir.toFile());
		TrackSpline spline = registry.replace(samples(0, 10));
		TrackRoute expected = route(List.of(spline), List.of(), "world", 0, 0, 0, 0, 10, 0, 3).orElseThrow();
		assertEquals(expected, registry.shortestRoute("world", 0, 0, 0, 0, 10, 0, 3).orElseThrow());
		assertEquals(List.of(spline), List.copyOf(registry.all()));
	}

	private static Optional<TrackRoute> route(
			List<TrackSpline> splines,
			List<TrackJunction> junctions,
			String world,
			double ax, double az, double radiusA,
			double bx, double bz, double radiusB,
			double spacing) {
		OptionalDouble length = TrackRouteQuery.shortestRouteLength(splines, junctions, world, ax, az, radiusA, bx, bz, radiusB);
		Optional<TrackRoute> route = TrackRouteQuery.shortestRoute(splines, junctions, world, ax, az, radiusA, bx, bz, radiusB, spacing);
		assertEquals(length.isPresent(), route.isPresent());
		if (route.isPresent()) {
			assertEquals(length.orElseThrow(), route.get().length(), 1e-6);
			assertTrue(!route.get().points().isEmpty());
			TrackSamplePoint start = route.get().points().getFirst();
			TrackSamplePoint end = route.get().points().getLast();
			assertTrue(Math.hypot(start.x() - ax, start.z() - az) <= radiusA + 1e-6);
			assertTrue(Math.hypot(end.x() - bx, end.z() - bz) <= radiusB + 1e-6);
			assertSteps(route.get(), !Double.isFinite(spacing) || spacing <= 0 ? 4 : Math.max(spacing, 0.5));
		}
		return route;
	}

	private static void assertSteps(TrackRoute route, double spacing) {
		for (int i = 1; i < route.points().size(); i++) {
			TrackSamplePoint a = route.points().get(i - 1);
			TrackSamplePoint b = route.points().get(i);
			double dx = b.x() - a.x();
			double dy = b.y() - a.y();
			double dz = b.z() - a.z();
			assertTrue(Math.sqrt(dx * dx + dy * dy + dz * dz) <= spacing + 1e-6);
		}
	}

	private static TrackSamplePoint point(TrackSpline spline, double x, double z) {
		return new TrackSamplePoint(spline.getId(), x, 0, z);
	}

	private static TrackSpline samples(double... z) {
		List<double[]> points = new ArrayList<>();
		for (double at : z) {
			points.add(new double[] {0, 0, at});
		}
		return TrackSpline.fromPoints(UUID.randomUUID(), "world", false, points);
	}

	private static TrackSpline rectangle() {
		return TrackSpline.fromPoints(UUID.randomUUID(), "world", true,
				List.of(new double[] {0, 0, 0}, new double[] {30, 0, 0}, new double[] {30, 0, 10}, new double[] {0, 0, 10}));
	}

	private static TrackSpline breakAt(TrackSpline spline, int... edges) {
		for (int edge : edges) {
			spline = spline.withSegment(edge, spline.segment(edge).withBroken(true));
		}
		return spline;
	}

	private static TrackSpline line(String world, double x0, double z0, double x1, double z1) {
		return TrackSpline.fromPoints(UUID.randomUUID(), world, false,
				List.of(new double[] {x0, 0, z0}, new double[] {x1, 0, z1}));
	}

	private static TrackJunction join(TrackSpline stem, double s, TrackSpline branch) {
		return new TrackJunction(UUID.randomUUID(), stem.getId(), s, -1, TrackJunction.Side.RIGHT,
				branch == null ? null : branch.getId(), false, branch == null ? 0 : branch.length());
	}
}
