package net.tfminecraft.vehicleframework.tracks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.json.simple.JSONObject;
import org.junit.jupiter.api.Test;

class TrackSplineTest {

	@Test
	void fromPoints_rejectsSingleSample() {
		assertThrows(IllegalArgumentException.class, () -> TrackSpline.fromPoints(
				UUID.randomUUID(), "world", false, List.of(new double[] {0, 0, 0})));
	}

	@Test
	void sampleAt_southAlongPlusZ_hasYawZero() {
		TrackSpline spline = TrackSpline.fromPoints(
				UUID.randomUUID(), "world", false,
				List.of(new double[] {0, 64, 0}, new double[] {0, 64, 10}));
		TrackPose mid = spline.sampleAt(5);
		assertEquals(0, mid.x, 1e-9);
		assertEquals(64, mid.y, 1e-9);
		assertEquals(5, mid.z, 1e-9);
		assertEquals(0f, mid.yaw, 0.01f);
	}

	@Test
	void sampleAt_westAlongMinusX_hasYawNinety() {
		TrackSpline spline = TrackSpline.fromPoints(
				UUID.randomUUID(), "world", false,
				List.of(new double[] {0, 64, 0}, new double[] {-10, 64, 0}));
		TrackPose mid = spline.sampleAt(5);
		assertEquals(-5, mid.x, 1e-9);
		assertEquals(90f, mid.yaw, 0.01f);
	}

	@Test
	void advance_stopsAtBrokenSegment() {
		TrackSpline spline = TrackSpline.fromPoints(
				UUID.randomUUID(), "world", false,
				List.of(new double[] {0, 0, 0}, new double[] {0, 0, 10}, new double[] {0, 0, 20}));
		spline = spline.withSegment(1, new TrackSegment(1, true, 1.0));
		TrackAdvance move = spline.advance(5, 8);
		assertTrue(move.stoppedAtBreak);
		assertEquals(10, move.s, 1e-9);
	}

	@Test
	void advance_backwardsStopsAtBrokenSegmentBehind() {
		TrackSpline spline = TrackSpline.fromPoints(
				UUID.randomUUID(), "world", false,
				List.of(new double[] {0, 0, 0}, new double[] {0, 0, 10}, new double[] {0, 0, 20}));
		spline = spline.withSegment(0, new TrackSegment(0, true, 1.0));
		TrackAdvance move = spline.advance(15, -8);
		assertTrue(move.stoppedAtBreak);
		assertEquals(10, move.s, 1e-9);
	}

	@Test
	void segment_findsSegmentsStoredOutOfOrder() {
		TrackSpline straight = TrackSpline.fromPoints(
				UUID.randomUUID(), "world", false,
				List.of(new double[] {0, 0, 0}, new double[] {0, 0, 10}, new double[] {0, 0, 20},
						new double[] {0, 0, 30}));
		TrackSpline spline = new TrackSpline(
				straight.getId(), "world", false, straight.getSamples(),
				List.of(new TrackSegment(2, false, 1.0), new TrackSegment(1, true, 0.5),
						new TrackSegment(0, false, 1.0)));
		assertTrue(spline.segment(1).broken);
		assertEquals(0.5, spline.segment(1).health, 1e-9);
		assertFalse(spline.segment(2).broken);
		TrackAdvance move = spline.advance(25, -20);
		assertTrue(move.stoppedAtBreak);
		assertEquals(20, move.s, 1e-9);
	}

	@Test
	void sampleAt_longTrackWithRepeatedPoint_findsEachPosition() {
		List<double[]> points = new java.util.ArrayList<>();
		for (int z = 0; z <= 2000; z++) {
			points.add(new double[] {0, 64, z});
			if (z == 1000) {
				points.add(new double[] {0, 64, z});
			}
		}
		TrackSpline spline = TrackSpline.fromPoints(UUID.randomUUID(), "world", false, points);
		for (double s : new double[] {0, 0.25, 999.5, 1000, 1000.5, 1999.75, 2000}) {
			assertEquals(s, spline.sampleAt(s).z, 1e-9);
		}
		assertEquals(2000, spline.sampleAt(2500).z, 1e-9);
	}

	@Test
	void sampleAt_loopClosingEdge_runsBackToStart() {
		TrackSpline spline = TrackSpline.fromPoints(
				UUID.randomUUID(), "world", true,
				List.of(new double[] {0, 0, 0}, new double[] {10, 0, 0}, new double[] {10, 0, 10},
						new double[] {0, 0, 10}));
		TrackPose closing = spline.sampleAt(35);
		assertEquals(0, closing.x, 1e-9);
		assertEquals(5, closing.z, 1e-9);
	}

	@SuppressWarnings("unchecked")
	@Test
	void fromJson_samplesOutOfOrder_recomputesDistances() {
		JSONObject json = TrackSpline.fromPoints(
				UUID.randomUUID(), "world", false,
				List.of(new double[] {0, 0, 0}, new double[] {0, 0, 10}, new double[] {0, 0, 20},
						new double[] {0, 0, 30})).toJson();
		double[] stored = {0, 10, 5, 15};
		org.json.simple.JSONArray samples = (org.json.simple.JSONArray) json.get("samples");
		for (int i = 0; i < stored.length; i++) {
			((JSONObject) samples.get(i)).put("s", stored[i]);
		}
		TrackSpline spline = TrackSpline.fromJson(json);
		assertEquals(20, spline.getSamples().get(2).s, 1e-9);
		assertEquals(7, spline.sampleAt(7).z, 1e-9);
	}

	@Test
	void advance_openClampsAtEnd() {
		TrackSpline spline = TrackSpline.fromPoints(
				UUID.randomUUID(), "world", false,
				List.of(new double[] {0, 0, 0}, new double[] {0, 0, 10}));
		TrackAdvance move = spline.advance(9, 5);
		assertFalse(move.stoppedAtBreak);
		assertEquals(10, move.s, 1e-9);
	}

	@Test
	void advance_loopWraps() {
		TrackSpline spline = TrackSpline.fromPoints(
				UUID.randomUUID(), "world", true,
				List.of(new double[] {0, 0, 0}, new double[] {0, 0, 10}));
		double len = spline.length();
		assertEquals(20, len, 1e-9);
		TrackAdvance move = spline.advance(len - 1, 3);
		assertFalse(move.stoppedAtBreak);
		assertEquals(2, move.s, 1e-9);
	}

	@Test
	void promotedLoop_wrapsPastJoin() {
		TrackSpline open = TrackSpline.fromPoints(
				UUID.randomUUID(), "world", false,
				List.of(
						new double[] {0, 0, 0},
						new double[] {8, 0, 0},
						new double[] {8, 0, 8},
						new double[] {0.5, 0, 0}));
		assertFalse(open.isLoop());
		TrackSpline loop = open.promotedLoop(1.5);
		assertTrue(loop.isLoop());
		double len = loop.length();
		TrackAdvance move = loop.advance(len - 0.2, 1.0);
		assertFalse(move.stoppedAtBreak);
		assertTrue(move.s < 2.0);
	}

	@Test
	void fromJson_promotesLoopWhenEndsMeet() {
		TrackSpline open = TrackSpline.fromPoints(
				UUID.randomUUID(), "world", false,
				List.of(
						new double[] {0, 0, 0},
						new double[] {8, 0, 0},
						new double[] {8, 0, 8},
						new double[] {0.4, 0, 0}));
		assertFalse(open.isLoop());
		TrackSpline loaded = TrackSpline.fromJson(open.toJson());
		assertTrue(loaded.isLoop());
	}

	// json-simple 1.1 exposes raw containers; this fixture inserts a String key/value.
	@SuppressWarnings("unchecked")
	@Test
	void json_roundtripPreservesBrokenAndWorld() {
		UUID id = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
		TrackSpline spline = TrackSpline.fromPoints(
				id, "tracks", false,
				List.of(new double[] {1, 2, 3}, new double[] {1, 2, 13}));
		spline = spline.withSegment(0, new TrackSegment(0, true, 0.5));
		JSONObject json = spline.toJson();
		json.put("visuals", "ignored");
		TrackSpline loaded = TrackSpline.fromJson(json);
		assertEquals(id, loaded.getId());
		assertEquals("tracks", loaded.getWorld());
		assertFalse(loaded.isLoop());
		assertTrue(loaded.getSegments().get(0).broken);
		assertEquals(0.5, loaded.getSegments().get(0).health, 1e-9);
		assertEquals(10, loaded.length(), 1e-9);
	}

	@Test
	void nearestS_onStraightSegment() {
		TrackSpline spline = TrackSpline.fromPoints(
				UUID.randomUUID(), "world", false,
				List.of(new double[] {0, 0, 0}, new double[] {0, 0, 10}));
		assertEquals(4, spline.nearestS(0, 1, 4), 1e-6);
		assertEquals(0, spline.nearestS(0, 0, -5), 1e-6);
		assertEquals(10, spline.nearestS(0, 0, 50), 1e-6);
	}
}
