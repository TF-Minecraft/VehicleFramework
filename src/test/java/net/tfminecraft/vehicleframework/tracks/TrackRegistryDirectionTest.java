package net.tfminecraft.vehicleframework.tracks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrackRegistryDirectionTest {

	@Test
	void connectFromStartToEndKeepsBothTracksDirection(@TempDir Path dir) throws Exception {
		TrackRegistry registry = new TrackRegistry(dir.toFile());
		TrackSpline a = registry.lay("world", 0, 64, 30, 0, 64, 40).spline();
		registry.lay("world", 0, 64, 0, 0, 64, 10);
		TrackSpline joined = registry.lay("world", 0, 64, 30, 0, 64, 10).spline();
		assertEquals(a.getId(), joined.getId());
		assertEquals(0, joined.first().z, 0.5);
		assertEquals(40, joined.last().z, 0.5);
	}

	@Test
	void connectingTwoStartsReversesTheTrackWithoutTrains(@TempDir Path dir) throws Exception {
		TrackRegistry registry = new TrackRegistry(dir.toFile());
		TrackSpline a = registry.lay("world", 0, 64, 30, 0, 64, 40).spline();
		registry.lay("world", 0, 64, 10, 0, 64, 0);
		registry.occupiedBy(a.getId()::equals);
		TrackSpline joined = registry.lay("world", 0, 64, 30, 0, 64, 10).spline();
		assertEquals(0, joined.first().z, 0.5);
		assertEquals(40, joined.last().z, 0.5);
	}

	@Test
	void connectingTwoStartsKeepsOccupiedDropTrackDirection(@TempDir Path dir) throws Exception {
		TrackRegistry registry = new TrackRegistry(dir.toFile());
		registry.lay("world", 0, 64, 30, 0, 64, 40);
		TrackSpline b = registry.lay("world", 0, 64, 10, 0, 64, 0).spline();
		registry.occupiedBy(b.getId()::equals);
		TrackSpline joined = registry.lay("world", 0, 64, 30, 0, 64, 10).spline();
		assertEquals(40, joined.first().z, 0.5);
		assertEquals(0, joined.last().z, 0.5);
	}

	@Test
	void connectingTwoOccupiedStartsIsRefused(@TempDir Path dir) throws Exception {
		TrackRegistry registry = new TrackRegistry(dir.toFile());
		TrackSpline a = registry.lay("world", 0, 64, 30, 0, 64, 40).spline();
		TrackSpline b = registry.lay("world", 0, 64, 10, 0, 64, 0).spline();
		registry.occupiedBy(Set.of(a.getId(), b.getId())::contains);
		assertThrows(TrackLayException.class, () -> registry.lay("world", 0, 64, 30, 0, 64, 10));
		assertEquals(2, registry.inWorld("world").size());
		assertEquals(30, registry.get(a.getId()).orElseThrow().first().z, 0.5);
	}

	@Test
	void closingLoopFromStartKeepsTrackDirection(@TempDir Path dir) throws Exception {
		TrackRegistry registry = new TrackRegistry(dir.toFile());
		TrackSpline arc = registry.replace(arc());
		assertTrue(!arc.isLoop(), "Setup: the track starts open");
		TrackSample first = arc.first();
		TrackSample second = arc.getSamples().get(1);
		TrackSample last = arc.last();
		TrackSpline loop = registry.lay("world", first.x, first.y, first.z, last.x, last.y, last.z).spline();
		assertTrue(loop.isLoop());
		assertEquals(arc.getId(), loop.getId());
		assertEquals(first.x, loop.first().x, 1e-9);
		assertEquals(first.z, loop.first().z, 1e-9);
		assertEquals(second.x, loop.getSamples().get(1).x, 1e-9);
		assertEquals(second.z, loop.getSamples().get(1).z, 1e-9);
	}

	@Test
	void digTargetIncludesTurnoutDroppedWithShortStemPiece(@TempDir Path dir) throws Exception {
		TrackRegistry registry = new TrackRegistry(dir.toFile());
		TrackSpline stem = registry.lay("world", 0, 64, 0, 0, 64, 40).spline();
		TrackJunction placed = registry.putJunction(new TrackJunction(
				UUID.randomUUID(), stem.getId(), 34, 1, TrackJunction.Side.RIGHT, null));
		TrackSpline branch = registry.layBranch(placed.id, "world", null, 2, 64, 52);
		stem = registry.get(stem.getId()).orElseThrow();

		TrackSample far = stem.getSamples().get(10);
		assertEquals(1, registry.digTarget("world", far.x, far.y, far.z).orElseThrow().spans().size());

		// Cutting at s=32 leaves the frog at s=34 on a 7-block piece, under the 8 minimum.
		int near = indexAt(stem, 32);
		TrackSample cut = stem.getSamples().get(near);
		TrackRegistry.DigTarget target = registry.digTarget("world", cut.x, cut.y, cut.z).orElseThrow();
		assertEquals(2, target.spans().size());
		assertEquals(branch.getId(), target.spans().get(1).trackId());

		registry.digAt(stem, near);
		assertTrue(registry.getJunction(placed.id).isEmpty(), "The dig must really drop that turnout");
	}

	@Test
	void digTargetSkipsTurnoutWhenFrogStaysOnLongPiece(@TempDir Path dir) throws Exception {
		TrackRegistry registry = new TrackRegistry(dir.toFile());
		TrackSpline stem = registry.lay("world", 0, 64, 0, 0, 64, 40).spline();
		TrackJunction placed = registry.putJunction(new TrackJunction(
				UUID.randomUUID(), stem.getId(), 34, 1, TrackJunction.Side.RIGHT, null));
		registry.layBranch(placed.id, "world", null, 2, 64, 52);
		stem = registry.get(stem.getId()).orElseThrow();
		// Digging next to the end leaves a one-sample stub; the frog stays on the 38-block piece.
		int nearEnd = stem.getSamples().size() - 2;
		TrackSample cut = stem.getSamples().get(nearEnd);
		assertEquals(1, registry.digTarget("world", cut.x, cut.y, cut.z).orElseThrow().spans().size());
		registry.digAt(stem, nearEnd);
		assertTrue(registry.getJunction(placed.id).isPresent());
	}

	private static int indexAt(TrackSpline spline, double s) {
		List<TrackSample> samples = spline.getSamples();
		for (int i = 0; i < samples.size(); i++) {
			if (Math.abs(samples.get(i).s - s) < 1e-6) {
				return i;
			}
		}
		throw new AssertionError("No sample at s=" + s);
	}

	// A racetrack open along one straight, so the closing piece runs straight.
	private static TrackSpline arc() {
		List<double[]> points = new ArrayList<>();
		for (int z = 10; z <= 40; z++) {
			points.add(new double[] {0, 64, z});
		}
		addSemicircle(points, 20, 40, Math.PI);
		for (int z = 39; z >= 0; z--) {
			points.add(new double[] {40, 64, z});
		}
		addSemicircle(points, 20, 0, 0);
		return TrackSpline.fromPoints(UUID.randomUUID(), "world", false, points);
	}

	private static void addSemicircle(List<double[]> points, double cx, double cz, double from) {
		int steps = 63;
		for (int i = 1; i < steps; i++) {
			double angle = from - Math.PI * i / steps;
			points.add(new double[] {cx + 20 * Math.cos(angle), 64, cz + 20 * Math.sin(angle)});
		}
	}

}
