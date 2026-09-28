package net.tfminecraft.vehicleframework.tracks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class TrackVisualDiffTest {

	private static List<double[]> eastward(int blocks) {
		List<double[]> points = new ArrayList<>();
		for (int x = 0; x <= blocks; x++) {
			points.add(new double[] {x + 0.5, 64, 8.5});
		}
		return points;
	}

	@Test
	void changedChunks_diggingTheEnd_onlyTouchesTheLastChunk() {
		UUID id = UUID.randomUUID();
		List<double[]> points = eastward(200);
		TrackSpline before = TrackSpline.fromPoints(id, "world", false, points);
		TrackSpline after = TrackSpline.fromPoints(id, "world", false, points.subList(0, points.size() - 1));
		Set<Long> changed = TrackVisualDiff.changedChunks(before.visuals(), after.visuals());
		assertEquals(Set.of(TrackChunks.key(12, 0)), changed);
	}

	@Test
	void changedChunks_breakingOnePiece_keepsTheChunksBeforeIt() {
		TrackSpline before = TrackSpline.fromPoints(UUID.randomUUID(), "world", false, eastward(200));
		TrackSpline after = before.withSegment(100, before.segment(100).withBroken(true));
		Set<Long> changed = TrackVisualDiff.changedChunks(before.visuals(), after.visuals());
		// Pieces after the gap group from a new start, so later chunks change too.
		assertTrue(changed.contains(TrackChunks.key(6, 0)));
		for (int cx = 0; cx < 6; cx++) {
			assertTrue(!changed.contains(TrackChunks.key(cx, 0)), "chunk " + cx);
		}
	}

	@Test
	void changedChunks_trackGoneFromAChunk_listsThatChunk() {
		UUID id = UUID.randomUUID();
		TrackSpline before = TrackSpline.fromPoints(id, "world", false, eastward(40));
		TrackSpline after = TrackSpline.fromPoints(id, "world", false, eastward(20));
		Set<Long> changed = TrackVisualDiff.changedChunks(before.visuals(), after.visuals());
		assertTrue(changed.contains(TrackChunks.key(2, 0)));
		assertTrue(changed.contains(TrackChunks.key(1, 0)));
		assertEquals(2, changed.size());
	}

	@Test
	void changedChunks_sameBake_changesNothing() {
		TrackSpline spline = TrackSpline.fromPoints(UUID.randomUUID(), "world", false, eastward(100));
		assertTrue(TrackVisualDiff.changedChunks(spline.visuals(), TrackVisualBake.bake(spline)).isEmpty());
	}
}
