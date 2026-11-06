package net.tfminecraft.vehicleframework.tracks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.json.simple.JSONObject;
import org.junit.jupiter.api.Test;

class ThrottleTapeTest {

	@Test
	void samplesKeepTheirBodyOrientationThroughJson() {
		java.util.UUID stem = java.util.UUID.randomUUID();
		java.util.UUID branch = java.util.UUID.randomUUID();
		ThrottleTape tape = new ThrottleTape(stem.toString());
		tape.tryAppend(20, -1, 40, stem.toString(), null, 1);
		tape.tryAppend(5, 1, 40, branch.toString(), null, -1);
		ThrottleTape loaded = ThrottleTape.fromJson(tape.toJson());
		assertEquals(1, loaded.sample(0).orientation);
		assertEquals(-1, loaded.sample(1).orientation);
		assertEquals(branch.toString(), loaded.sample(1).resolvedSpline(stem.toString()));
	}

	@Test
	void emptyTapeHasNoSamples() {
		ThrottleTape tape = new ThrottleTape("spline");
		assertTrue(tape.isEmpty());
		assertEquals(0, tape.size());
		assertNull(tape.last());
		assertEquals(-1, tape.nextSplineChange(0));
		assertEquals(0, tape.totalTicks());
	}

	@Test
	void tryAppend_sameSAndThrottle_incrementsHold() {
		ThrottleTape tape = new ThrottleTape("spline");
		assertEquals(ThrottleTape.AppendResult.ADDED, tape.tryAppend(5, 1, 0));
		assertEquals(ThrottleTape.AppendResult.HELD, tape.tryAppend(5.2, 1, 0));
		assertEquals(ThrottleTape.AppendResult.HELD, tape.tryAppend(5.4, 1, 0));
		assertEquals(1, tape.getSamples().size());
		assertEquals(3, tape.last().holdTicks);
		assertEquals(3, tape.totalTicks());
	}

	@Test
	void jsonRoundtrip_preservesHold() {
		ThrottleTape tape = new ThrottleTape("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
		tape.tryAppend(0, 1, 20);
		tape.tryAppend(4, 1, 40);
		ThrottleTape loaded = ThrottleTape.fromJson(tape.toJson());
		assertEquals(40, loaded.sample(1).throttle);
		assertEquals(1, loaded.getSamples().get(0).holdTicks);
		assertFalse(loaded.isEmpty());
	}

	// json-simple 1.1 exposes raw containers; these fixtures contain only JSON-compatible values.
	@SuppressWarnings("unchecked")
	@Test
	void fromJson_missingHold_defaultsToOne() {
		JSONObject root = new JSONObject();
		root.put("splineId", "spline");
		org.json.simple.JSONArray arr = new org.json.simple.JSONArray();
		JSONObject o = new JSONObject();
		o.put("s", 0d);
		o.put("sign", 1L);
		o.put("throttle", 0L);
		arr.add(o);
		root.put("samples", arr);
		ThrottleTape loaded = ThrottleTape.fromJson(root);
		assertEquals(1, loaded.getSamples().get(0).holdTicks);
	}

	@Test
	void tryAppend_splineChange_addsSample() {
		ThrottleTape tape = new ThrottleTape("stem");
		assertEquals(ThrottleTape.AppendResult.ADDED, tape.tryAppend(5, 1, 40, "stem", null));
		assertEquals(ThrottleTape.AppendResult.HELD, tape.tryAppend(5.2, 1, 40, "stem", null));
		assertEquals(ThrottleTape.AppendResult.ADDED, tape.tryAppend(0, 1, 40, "branch", "junc-1"));
		assertEquals(2, tape.getSamples().size());
		assertEquals("branch", tape.getSamples().get(1).splineId);
		assertEquals("junc-1", tape.getSamples().get(1).junctionId);
	}

	@Test
	void jsonRoundtrip_sampleSplineAndJunction() {
		ThrottleTape tape = new ThrottleTape("stem-id");
		tape.tryAppend(1, 1, 20, "stem-id", null);
		tape.tryAppend(0, 1, 40, "branch-id", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
		ThrottleTape loaded = ThrottleTape.fromJson(tape.toJson());
		assertEquals("branch-id", loaded.getSamples().get(1).splineId);
		assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", loaded.getSamples().get(1).junctionId);
		assertNull(loaded.getSamples().get(0).splineId);
	}

	// json-simple 1.1 exposes raw containers; these fixtures contain only JSON-compatible values.
	@SuppressWarnings("unchecked")
	@Test
	void fromJson_missingSampleSpline_inheritsOrigin() {
		JSONObject root = new JSONObject();
		root.put("splineId", "origin");
		org.json.simple.JSONArray arr = new org.json.simple.JSONArray();
		JSONObject o = new JSONObject();
		o.put("s", 4d);
		o.put("sign", 1L);
		o.put("throttle", 70L);
		arr.add(o);
		root.put("samples", arr);
		ThrottleTape loaded = ThrottleTape.fromJson(root);
		assertEquals("origin", loaded.sample(0).resolvedSpline(loaded.getSplineId()));
		assertEquals(70, loaded.sample(0).throttle);
	}

	@Test
	void samplesWrapRoundTheTape() {
		ThrottleTape tape = new ThrottleTape("a", List.of(new ThrottleTape.Sample(0, 1, 10),
				new ThrottleTape.Sample(5, 1, 20), new ThrottleTape.Sample(9, 1, 30)));
		assertEquals(10, tape.sample(3).throttle);
		assertEquals(30, tape.sample(-1).throttle);
		assertEquals(30, tape.last().throttle);
	}

	@Test
	void nextSplineChangeLooksAheadRoundTheEnd() {
		ThrottleTape tape = new ThrottleTape("stem", List.of(
				new ThrottleTape.Sample(0, 1, 10, 1, "stem", null),
				new ThrottleTape.Sample(5, 1, 10, 1, "stem", null),
				new ThrottleTape.Sample(0, 1, 10, 1, "branch", null),
				new ThrottleTape.Sample(5, 1, 10, 1, "branch", null),
				new ThrottleTape.Sample(9, 1, 10, 1, "stem", null)));
		assertEquals(2, tape.nextSplineChange(0));
		assertEquals(2, tape.nextSplineChange(1));
		assertEquals(4, tape.nextSplineChange(2));
		assertEquals(2, tape.nextSplineChange(4));
		assertEquals(2, tape.nextSplineChange(-1));
		assertEquals(-1, new ThrottleTape("one", List.of(new ThrottleTape.Sample(0, 1, 1),
				new ThrottleTape.Sample(4, 1, 1))).nextSplineChange(0));
		tape.tryAppend(20, 1, 50, "elsewhere", null);
		assertEquals(5, tape.nextSplineChange(4));
	}

	@Test
	void nearestIndexPrefersTheTravelDirectionThenTapeOrder() {
		// A shuttle: out along +s, back along -s over the same rails.
		ThrottleTape tape = new ThrottleTape("line", List.of(
				new ThrottleTape.Sample(0, 1, 50), new ThrottleTape.Sample(10, 1, 50),
				new ThrottleTape.Sample(20, 1, 0), new ThrottleTape.Sample(10, -1, -50),
				new ThrottleTape.Sample(0, -1, 0)));
		assertEquals(1, tape.nearestIndex("line", 11, 1, -1, 4, 30, false));
		assertEquals(3, tape.nearestIndex("line", 11, -1, -1, 4, 30, false));
		// Within reach, tape order from the old cursor beats distance.
		assertEquals(2, tape.nearestIndex("line", 9, 1, 2, 12, 30, false));
		// None in reach: none.
		assertEquals(-1, tape.nearestIndex("line", 26, 1, -1, 4, 30, false));
		assertEquals(-1, tape.nearestIndex("other", 5, 1, -1, 4, 30, false));
		assertEquals(-1, tape.nearestIndex(null, 5, 1, -1, 4, 30, false));
	}

	@Test
	void alongTakesTheShortWayRoundALoop() {
		assertEquals(4, ThrottleTape.along(98, 2, 100, true), 1e-9);
		assertEquals(-4, ThrottleTape.along(2, 98, 100, true), 1e-9);
		assertEquals(-96, ThrottleTape.along(98, 2, 100, false), 1e-9);
		assertEquals(5, ThrottleTape.along(0, 5, 0, true), 1e-9);
	}
}
