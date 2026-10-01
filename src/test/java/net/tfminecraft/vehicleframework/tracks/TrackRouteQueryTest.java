package net.tfminecraft.vehicleframework.tracks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class TrackRouteQueryTest {

	@Test
	void straightSpline() {
		// Slope 3-4-5: arc length 5, horizontal run 4. The query ignores height.
		TrackSpline spline = TrackSpline.fromPoints(
				UUID.randomUUID(), "world", false,
				List.of(new double[] {0, 0, 0}, new double[] {0, 3, 4}));
		OptionalDouble route = route(List.of(spline), List.of(), "world", 0, 2, 0, 0, 4, 0);
		assertLength(route, 2.5);
	}

	@Test
	void twoSplinesJoinedByJunction() {
		TrackSpline stem = line("world", 0, 0, 0, 80);
		TrackSpline branch = line("world", 0, 40, 50, 40);
		TrackJunction open = join(stem, 40, branch);
		TrackJunction thrown = open.withThrown(true).withFacing(1);
		assertLength(route(List.of(stem, branch), List.of(open), "world", 0, 10, 0, 20, 40, 0), 50);
		assertLength(route(List.of(stem, branch), List.of(thrown), "world", 0, 10, 0, 20, 40, 0), 50);
	}

	@Test
	void threeInAChain() {
		TrackSpline first = line("world", 0, 0, 100, 0);
		TrackSpline second = line("world", 100, 0, 100, 60);
		TrackSpline third = line("world", 100, 60, 140, 60);
		List<TrackJunction> junctions = List.of(join(first, 100, second), join(second, 60, third));
		OptionalDouble route = route(
				List.of(first, second, third), junctions, "world", 0, 0, 0, 140, 60, 0);
		assertLength(route, 200);
	}

	@Test
	void disconnectedPair_isEmpty() {
		TrackSpline left = line("world", 0, 0, 0, 20);
		TrackSpline right = line("world", 100, 0, 120, 0);
		OptionalDouble route = route(List.of(left, right), List.of(), "world", 0, 10, 0, 110, 0, 0);
		assertTrue(route.isEmpty());
	}

	@Test
	void loop_shortWayWins() {
		TrackSpline loop = TrackSpline.fromPoints(
				UUID.randomUUID(), "world", true,
				List.of(
						new double[] {0, 0, 0},
						new double[] {30, 0, 0},
						new double[] {30, 0, 10},
						new double[] {0, 0, 10}));
		assertEquals(80, loop.length(), 1e-9);
		assertLength(route(List.of(loop), List.of(), "world", 0, 0, 0, 0, 10, 0), 10);
		assertLength(route(List.of(loop), List.of(), "world", 0, 0, 0, 30, 0, 0), 30);
	}

	@Test
	void noTrackInRadius_isEmpty() {
		TrackSpline spline = line("world", 0, 0, 0, 50);
		OptionalDouble route = route(List.of(spline), List.of(), "world", 0, 10, 2, 10, 10, 2);
		assertTrue(route.isEmpty());
	}

	@Test
	void bothAreasOnTheSameTrack() {
		TrackSpline spline = TrackSpline.fromPoints(
				UUID.randomUUID(), "world", false,
				List.of(
						new double[] {0, 0, 0},
						new double[] {0, 0, 10},
						new double[] {0, 0, 30}));
		assertLength(route(List.of(spline), List.of(), "world", 0, 10, 5, 0, 25, 2), 8);
		assertLength(route(List.of(spline), List.of(), "world", 0, 10, 5, 0, 14, 2), 0);
	}

	@Test
	void incompleteJunction_isEmpty() {
		TrackSpline stem = line("world", 0, 0, 80, 0);
		TrackSpline loose = line("world", 40, 0, 40, 50);
		TrackJunction open = join(stem, 40, null);
		OptionalDouble route = route(List.of(stem, loose), List.of(open), "world", 0, 0, 0, 40, 50, 0);
		assertTrue(route.isEmpty());
	}

	@Test
	void straightBreakBetweenAreas_isEmpty() {
		TrackSpline spline = breakAt(samples(0, 40, 80, 120), 1);
		OptionalDouble route = route(List.of(spline), List.of(), "world", 0, 0, 0, 0, 120, 0);
		assertTrue(route.isEmpty());
	}

	@Test
	void breakBeyondFarArea_staysConnected() {
		TrackSpline spline = breakAt(samples(0, 40, 80, 120), 2);
		assertLength(route(List.of(spline), List.of(), "world", 0, 0, 0, 0, 40, 0), 40);
	}

	@Test
	void loop_breakOnShortSide_takesLongWay() {
		// Closing edge is fromIndex 3: last sample back to the first, 10 blocks.
		TrackSpline loop = breakAt(rectangle(), 3);
		assertLength(route(List.of(loop), List.of(), "world", 0, 0, 0, 0, 10, 0), 70);
	}

	@Test
	void loop_breaksOnBothSides_isEmpty() {
		TrackSpline loop = breakAt(rectangle(), 3, 1);
		OptionalDouble route = route(List.of(loop), List.of(), "world", 0, 0, 0, 0, 10, 0);
		assertTrue(route.isEmpty());
	}

	@Test
	void junctionStemBrokenBetweenJunctionAndA_isEmpty() {
		TrackSpline stem = breakAt(samples(0, 30, 60, 90), 1);
		TrackSpline branch = line("world", 0, 90, 40, 90);
		TrackJunction junction = join(stem, 90, branch);
		OptionalDouble route = route(
				List.of(stem, branch), List.of(junction), "world", 0, 0, 0, 40, 90, 0);
		assertTrue(route.isEmpty());
	}

	@Test
	void brokenEdgeInsideRadius_staysConnected() {
		// Edge 0 is broken and sits inside A's radius of 25. Intact track runs
		// from that radius edge (s = 25) on to B at s = 100.
		TrackSpline spline = breakAt(samples(0, 10, 40, 100), 0);
		assertLength(route(List.of(spline), List.of(), "world", 0, 0, 25, 0, 100, 0), 75);
	}

	@Test
	void differentWorlds_isEmpty() {
		TrackSpline spline = line("alpha", 0, 0, 10, 0);
		assertTrue(route(List.of(spline), List.of(), "beta", 0, 0, 0, 10, 0, 0).isEmpty());
		assertTrue(route(List.of(spline), List.of(), "missing", 0, 0, 0, 10, 0, 0).isEmpty());

		TrackSpline stem = line("overworld", 0, 0, 30, 0);
		TrackSpline branch = line("nether", 15, 0, 15, 40);
		TrackJunction cross = join(stem, 15, branch);
		OptionalDouble route = route(
				List.of(stem, branch), List.of(cross), "overworld", 0, 0, 0, 15, 40, 1);
		assertTrue(route.isEmpty());
	}

	private static void assertLength(OptionalDouble route, double expected) {
		assertTrue(route.isPresent());
		assertEquals(expected, route.getAsDouble(), 1e-6);
	}

	private static OptionalDouble route(
			List<TrackSpline> splines,
			List<TrackJunction> junctions,
			String world,
			double ax, double az, double radiusA,
			double bx, double bz, double radiusB) {
		return TrackRouteQuery.shortestRouteLength(
				splines, junctions, world, ax, az, radiusA, bx, bz, radiusB);
	}

	private static TrackSpline samples(double... z) {
		List<double[]> points = new ArrayList<>();
		for (double at : z) {
			points.add(new double[] {0, 0, at});
		}
		return TrackSpline.fromPoints(UUID.randomUUID(), "world", false, points);
	}

	private static TrackSpline rectangle() {
		return TrackSpline.fromPoints(
				UUID.randomUUID(), "world", true,
				List.of(
						new double[] {0, 0, 0},
						new double[] {30, 0, 0},
						new double[] {30, 0, 10},
						new double[] {0, 0, 10}));
	}

	private static TrackSpline breakAt(TrackSpline spline, int... edges) {
		for (int edge : edges) {
			spline = spline.withSegment(edge, spline.segment(edge).withBroken(true));
		}
		return spline;
	}

	private static TrackSpline line(String world, double x0, double z0, double x1, double z1) {
		return TrackSpline.fromPoints(
				UUID.randomUUID(), world, false,
				List.of(new double[] {x0, 0, z0}, new double[] {x1, 0, z1}));
	}

	private static TrackJunction join(TrackSpline stem, double s, TrackSpline branch) {
		return new TrackJunction(
				UUID.randomUUID(),
				stem.getId(),
				s,
				-1,
				TrackJunction.Side.RIGHT,
				branch == null ? null : branch.getId(),
				false,
				branch == null ? 0 : branch.length());
	}
}
