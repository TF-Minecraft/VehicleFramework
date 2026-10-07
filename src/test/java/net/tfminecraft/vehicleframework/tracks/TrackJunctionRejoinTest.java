package net.tfminecraft.vehicleframework.tracks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.json.simple.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A turnout whose branch runs back into a line at a second frog, at the branch's end. */
class TrackJunctionRejoinTest {
	@TempDir
	Path dir;

	private static TrackSpline line(double x, double fromZ, double toZ) {
		return TrackSpline.fromPoints(UUID.randomUUID(), "world", false,
				List.of(new double[] {x, 64, fromZ}, new double[] {x, 64, toZ}));
	}

	private static int sampleNear(TrackSpline spline, double s) {
		List<TrackSample> samples = spline.getSamples();
		int best = 0;
		for (int i = 0; i < samples.size(); i++) {
			if (Math.abs(samples.get(i).s - s) < Math.abs(samples.get(best).s - s)) {
				best = i;
			}
		}
		return best;
	}

	/** Junction off the main line, then extend the branch back into the line further on. */
	private record Loop(TrackRegistry registry, TrackSpline stem, TrackJunction out, TrackJunction back,
			TrackSpline branch) {
	}

	private Loop passingLoop() throws Exception {
		TrackRegistry registry = new TrackRegistry(dir.toFile());
		TrackSpline stem = registry.replace(line(0, 0, 200));
		TrackSpline turnout = registry.layBranch(stem.getId(), 40, 1, "world", null, 4, 64, 66);
		TrackSample tip = turnout.last();
		double yaw = Math.toRadians(tip.yaw);
		TrackSample straight = registry.lay("world", tip.x, tip.y, tip.z,
				tip.x - 20 * Math.sin(yaw), 64, tip.z + 20 * Math.cos(yaw)).spline().last();
		TrackLayResult rejoined = registry.lay("world", straight.x, straight.y, straight.z, 0, 64, 130);
		assertEquals(TrackLayResult.Kind.CONNECT, rejoined.kind);
		TrackSpline branch = rejoined.spline();
		assertEquals(turnout.getId(), branch.getId());
		List<TrackJunction> byBranch = registry.junctionsByBranch(branch.getId());
		assertEquals(2, byBranch.size());
		TrackJunction out = registry.branchJunctionAt(branch.getId(), false).orElseThrow();
		TrackJunction back = registry.branchJunctionAt(branch.getId(), true).orElseThrow();
		return new Loop(registry, stem, out, back, branch);
	}

	@Test
	void extendingABranchOntoTheLineJoinsItAtASecondFrog() throws Exception {
		Loop loop = passingLoop();
		assertEquals(40, loop.out.s, 1e-6);
		assertEquals(loop.stem.getId(), loop.back.stemSplineId);
		assertEquals(130, loop.back.s, 0.5);
		// Trains running back down the line can take it; trains coming off the branch carry on up it.
		assertEquals(-1, loop.back.facingSign);
		assertEquals(1, loop.back.crossingSign());
		assertEquals(TrackJunction.Side.LEFT, loop.out.side);
		assertEquals(TrackJunction.Side.RIGHT, loop.back.side);
		TrackSample end = loop.branch.last();
		assertEquals(0, end.x, 1e-6);
		assertEquals(130, end.z, 0.5);
		assertTrue(loop.back.turnoutEndS > 8);
		// The branch's ends meet frogs, so neither is free to lay from.
		assertTrue(loop.registry.findEnd("world", end.x, end.y, end.z).isEmpty());
	}

	@Test
	void aTrainRunsOutAlongTheBranchAndBackOntoTheLine() throws Exception {
		Loop loop = passingLoop();
		TrainRoute route = new TrainRoute(loop.registry, Map.of(loop.out.id, true));
		TrainRoute.Walk walk = route.walk(new TrainRoute.Position(loop.stem.getId(), 30, 1),
				10 + loop.branch.length() + 20);
		assertEquals(loop.stem.getId(), walk.position().splineId());
		assertEquals(loop.back.s + 20, walk.position().s(), 1e-6);
		assertEquals(1, walk.position().orientation());
		assertEquals(0, walk.missing(), 1e-9);
		assertTrue(walk.junctions().get(loop.out.id));
		assertTrue(walk.junctions().get(loop.back.id));

		// Down the line, the second frog sends the train backwards along the branch and out at the first.
		TrainRoute back = new TrainRoute(loop.registry, Map.of(loop.back.id, true));
		TrainRoute.Walk home = back.walk(new TrainRoute.Position(loop.stem.getId(), loop.back.s + 5, 1),
				-(5 + loop.branch.length() + 5));
		assertEquals(loop.stem.getId(), home.position().splineId());
		assertEquals(35, home.position().s(), 1e-6);
		assertEquals(1, home.position().orientation());
	}

	@Test
	void theShortestRouteGoesRoundTheLoop() throws Exception {
		Loop loop = passingLoop();
		TrackSample tip = loop.branch.getSamples().get(loop.branch.getSamples().size() - 8);
		double round = loop.registry.shortestRouteLength("world", tip.x, tip.z, 0.5, 0, 170, 0.5).orElseThrow();
		// Without the far frog the branch is a dead end, so the route goes back by the first frog.
		loop.registry.deleteJunction(loop.back.id);
		double back = loop.registry.shortestRouteLength("world", tip.x, tip.z, 0.5, 0, 170, 0.5).orElseThrow();
		assertTrue(round < 50, "round " + round);
		assertTrue(back > loop.branch.length(), "back " + back);
	}

	@Test
	void diggingTheRejoinTurnoutLeavesTheRestOfTheBranch() throws Exception {
		Loop loop = passingLoop();
		int index = sampleNear(loop.branch, loop.branch.length() - 20);
		TrackSample at = loop.branch.getSamples().get(index);
		TrackRegistry.DigTarget target = loop.registry.digTarget("world", at.x, at.y, at.z).orElseThrow();
		assertEquals(loop.branch.getId(), target.spline().getId());
		assertEquals(loop.branch.length() - loop.back.turnoutEndS / 2, target.spans().get(0).centreS(), 1e-6);
		DigResult dug = loop.registry.digAt(loop.branch, index);
		assertTrue(dug.removedJunctionTurnout);
		assertTrue(loop.registry.getJunction(loop.back.id).isEmpty());
		TrackSpline left = loop.registry.get(loop.branch.getId()).orElseThrow();
		assertTrue(left.length() < loop.branch.length() - loop.back.turnoutEndS + 1e-6);
		assertEquals(loop.out.id, loop.registry.branchJunctionAt(left.getId(), false).orElseThrow().id);
	}

	@Test
	void splittingTheBranchKeepsEachFrogOnItsOwnPiece() throws Exception {
		Loop loop = passingLoop();
		TrackSpline branch = loop.branch;
		int middle = sampleNear(branch, loop.out.turnoutEndS + 12);
		DigResult dug = loop.registry.digAt(branch, middle);
		assertEquals(DigResult.Kind.SPLIT, dug.kind);
		TrackJunction out = loop.registry.getJunction(loop.out.id).orElseThrow();
		TrackJunction back = loop.registry.getJunction(loop.back.id).orElseThrow();
		assertEquals(branch.getId(), out.branchSplineId);
		assertFalse(out.atEnd);
		assertFalse(back.branchSplineId.equals(branch.getId()));
		assertTrue(back.atEnd);
	}

	@Test
	void aJunctionWithNoTurnoutLengthGoesWhenItsFrogEndIsDug() throws Exception {
		Loop loop = passingLoop();
		loop.registry.putJunction(loop.back.withTurnoutEndS(0));
		loop.registry.digAt(loop.branch, loop.branch.getSamples().size() - 1);
		assertTrue(loop.registry.getJunction(loop.back.id).isEmpty());
		assertTrue(loop.registry.getJunction(loop.out.id).isPresent());
	}

	@Test
	void diggingTheFirstFrogsEndLeavesTheSecondOnTheRest() throws Exception {
		Loop loop = passingLoop();
		loop.registry.putJunction(loop.out.withTurnoutEndS(0));
		DigResult dug = loop.registry.digAt(loop.branch, 1);
		assertTrue(loop.registry.getJunction(loop.out.id).isEmpty());
		TrackJunction back = loop.registry.getJunction(loop.back.id).orElseThrow();
		assertEquals(dug.kept.getId(), back.branchSplineId);
		assertTrue(back.atEnd);
	}

	@Test
	void aTurnoutCanCrossOverToAnotherLineInOneStroke() throws Exception {
		TrackRegistry registry = new TrackRegistry(dir.toFile());
		TrackSpline near = registry.replace(line(0, 0, 100));
		TrackSpline far = registry.replace(line(6, 0, 100));
		TrackLayResult laid = registry.layTurnout(near.getId(), 30, 1, "world", null, 6, 64, 58, 1000);
		assertEquals(TrackLayResult.Kind.CONNECT, laid.kind);
		TrackSpline crossover = laid.spline();
		TrackJunction from = registry.branchJunctionAt(crossover.getId(), false).orElseThrow();
		TrackJunction into = registry.branchJunctionAt(crossover.getId(), true).orElseThrow();
		assertEquals(near.getId(), from.stemSplineId);
		assertEquals(far.getId(), into.stemSplineId);
		// The crossover leaves each line along it before bending away, towards the other line both times.
		assertEquals(TrackJunction.Side.LEFT, from.side);
		assertEquals(TrackJunction.Side.LEFT, into.side);
		assertEquals(58, into.s, 0.5);
		TrainRoute route = new TrainRoute(registry, Map.of(from.id, true));
		TrainRoute.Walk walk = route.walk(new TrainRoute.Position(near.getId(), 20, 1),
				10 + crossover.length() + 10);
		assertEquals(far.getId(), walk.position().splineId());
		assertEquals(into.s + 10, walk.position().s(), 1e-6);
	}

	@Test
	void diggingACrossoverTakesBothItsJunctions() throws Exception {
		TrackRegistry registry = new TrackRegistry(dir.toFile());
		TrackSpline near = registry.replace(line(0, 0, 100));
		TrackSpline far = registry.replace(line(6, 0, 100));
		TrackSpline crossover = registry.layTurnout(near.getId(), 30, 1, "world", null, 6, 64, 58, 1000).spline();
		TrackSample middle = crossover.getSamples().get(crossover.getSamples().size() / 2);
		registry.digAt(crossover, crossover.getSamples().size() / 2);
		assertTrue(registry.get(crossover.getId()).isEmpty(), "dug at " + middle.x + "," + middle.z);
		assertTrue(registry.junctionsOn(near.getId()).isEmpty());
		assertTrue(registry.junctionsOn(far.getId()).isEmpty());
	}

	@Test
	void trimmingOneTurnoutKeepsTheFrogAtTheOtherEnd() throws Exception {
		Loop loop = passingLoop();
		// A first turnout recorded as nearly the whole branch leaves only its far end.
		loop.registry.putJunction(loop.out.withTurnoutEndS(loop.branch.length() - 1));
		DigResult dug = loop.registry.digAt(loop.branch, 1);
		assertTrue(dug.removedJunctionTurnout);
		assertTrue(loop.registry.getJunction(loop.out.id).isEmpty());
		TrackJunction back = loop.registry.getJunction(loop.back.id).orElseThrow();
		assertEquals(loop.branch.getId(), back.branchSplineId);
		assertTrue(back.atEnd);
		assertEquals(loop.branch.last().z, loop.registry.get(loop.branch.getId()).orElseThrow().last().z, 1e-9);
	}

	@Test
	void aBranchCannotJoinItsOwnLinesEnd() throws Exception {
		TrackRegistry registry = new TrackRegistry(dir.toFile());
		TrackSpline stem = registry.replace(line(0, 0, 100));
		// A balloon: off the line at 30, round below its start, and back up towards it.
		TrackSpline balloon = registry.replace(TrackSpline.fromPoints(UUID.randomUUID(), "world", false, List.of(
				new double[] {0, 64, 30}, new double[] {12, 64, 10}, new double[] {16, 64, -20},
				new double[] {8, 64, -40}, new double[] {0, 64, -30}, new double[] {0, 64, -21},
				new double[] {0, 64, -12})));
		registry.putJunction(new TrackJunction(UUID.randomUUID(), stem.getId(), 30, -1,
				TrackJunction.Side.LEFT, balloon.getId(), false, 10));
		TrackLayException refused = assertThrows(TrackLayException.class,
				() -> registry.lay("world", 0, 64, -12, 0, 64, 0));
		assertEquals("A branch can't join its own line's end. Join it part way along instead.", refused.getMessage());
		assertTrue(registry.get(balloon.getId()).isPresent());
		assertEquals(1, registry.junctionsOn(stem.getId()).size());
	}

	@Test
	void aJunctionGoneWithADroppedTurnoutIsNotBroughtBack() throws Exception {
		TrackRegistry registry = new TrackRegistry(dir.toFile());
		TrackSpline keep = registry.replace(line(0, 0, 40));
		TrackSpline drop = registry.replace(line(0, 50, 90));
		TrackSpline toKeep = registry.replace(line(40, 0, 40));
		TrackSpline toDrop = registry.replace(line(80, 0, 40));
		TrackJunction kept = registry.putJunction(new TrackJunction(UUID.randomUUID(), keep.getId(), 38, 1,
				TrackJunction.Side.LEFT, toKeep.getId()));
		TrackJunction dropped = registry.putJunction(new TrackJunction(UUID.randomUUID(), drop.getId(), 2, 1,
				TrackJunction.Side.LEFT, toDrop.getId()));
		// keep is itself a branch off the turnout that the join will drop.
		TrackJunction onDropped = registry.putJunction(new TrackJunction(UUID.randomUUID(), toDrop.getId(), 20, 1,
				TrackJunction.Side.LEFT, keep.getId()));
		registry.lay("world", 0, 64, 40, 0, 64, 50);
		assertTrue(registry.getJunction(kept.id).isPresent());
		assertTrue(registry.getJunction(dropped.id).isEmpty());
		assertTrue(registry.get(toDrop.getId()).isEmpty());
		assertTrue(registry.getJunction(onDropped.id).isEmpty());
	}

	@Test
	void extendingBesideALineDoesNotJoinIt() throws Exception {
		TrackRegistry registry = new TrackRegistry(dir.toFile());
		TrackSpline stem = registry.replace(line(0, 0, 100));
		registry.replace(line(1.25, 0, 20));
		TrackLayResult laid = registry.lay("world", 1.25, 64, 20, 1.25, 64, 40);
		assertEquals(TrackLayResult.Kind.APPEND, laid.kind);
		assertTrue(registry.junctionsOn(stem.getId()).isEmpty());
	}

	@Test
	void aTurnoutOntoAnotherTracksEndJoinsThatTrack() throws Exception {
		TrackRegistry registry = new TrackRegistry(dir.toFile());
		TrackSpline stem = registry.replace(line(0, 0, 100));
		TrackSpline other = registry.replace(TrackSpline.fromPoints(UUID.randomUUID(), "world", false,
				List.of(new double[] {8, 64, 60}, new double[] {8, 64, 90})));
		TrackSpline elsewhere = registry.replace(line(40, 0, 100));
		TrackJunction onOther = registry.putJunction(new TrackJunction(UUID.randomUUID(), elsewhere.getId(), 50, 1,
				TrackJunction.Side.LEFT, other.getId(), false, 4, true));
		TrackLayResult laid = registry.layTurnout(stem.getId(), 34, 1, "world", null, 8, 64, 60, 1000);
		assertEquals(TrackLayResult.Kind.CONNECT, laid.kind);
		assertTrue(registry.get(other.getId()).isEmpty());
		TrackSpline joined = laid.spline();
		assertEquals(90, joined.last().z, 1e-6);
		TrackJunction out = registry.branchJunctionAt(joined.getId(), false).orElseThrow();
		assertEquals(stem.getId(), out.stemSplineId);
		assertEquals(joined.getId(), registry.getJunction(onOther.id).orElseThrow().branchSplineId);
	}

	@Test
	void aTurnoutThatCannotMeetTheLineAlongItIsRefused() throws Exception {
		TrackRegistry registry = new TrackRegistry(dir.toFile());
		TrackSpline stem = registry.replace(line(0, 0, 100));
		TrackSpline across = registry.replace(TrackSpline.fromPoints(UUID.randomUUID(), "world", false,
				List.of(new double[] {-20, 64, 40}, new double[] {40, 64, 40})));
		TrackLayException refused = assertThrows(TrackLayException.class,
				() -> registry.layTurnout(stem.getId(), 30, 1, "world", null, 2, 64, 40, 1000));
		assertEquals("The track can't curve onto that line from here.", refused.getMessage());
		assertEquals(2, registry.all().size());
		assertTrue(registry.junctionsOn(across.getId()).isEmpty());
	}

	@Test
	void aTurnoutBackOntoItsOwnStemMustLeaveRoomBetweenTheFrogs() throws Exception {
		TrackRegistry registry = new TrackRegistry(dir.toFile());
		TrackSpline stem = registry.replace(line(0, 0, 100));
		assertThrows(TrackLayException.class,
				() -> registry.layTurnout(stem.getId(), 30, 1, "world", null, 0, 64, 40, 1000));
		assertTrue(registry.junctionsOn(stem.getId()).isEmpty());
	}

	@Test
	void aRejoinMustBeAffordableInFull() throws Exception {
		TrackRegistry registry = new TrackRegistry(dir.toFile());
		TrackSpline near = registry.replace(line(0, 0, 100));
		registry.replace(line(6, 0, 100));
		TrackLayException turnout = assertThrows(TrackLayException.class,
				() -> registry.layTurnout(near.getId(), 30, 1, "world", null, 6, 64, 58, 1));
		assertTrue(turnout.getMessage().startsWith("You need"), turnout.getMessage());
		TrackSample tip = registry.layBranch(near.getId(), 30, 1, "world", null, 3, 64, 50).last();
		TrackLayException rejoin = assertThrows(TrackLayException.class, () -> registry.lay("world", null,
				tip.x, tip.y, tip.z, 6, 64, 80, 1));
		assertTrue(rejoin.getMessage().startsWith("You need"), rejoin.getMessage());
		assertEquals(1, registry.junctionsOn(near.getId()).size());
	}

	@Test
	void layingBackwardsOntoATrackEndAlsoRejoins() throws Exception {
		TrackRegistry registry = new TrackRegistry(dir.toFile());
		TrackSpline stem = registry.replace(line(0, 0, 200));
		TrackSpline spur = registry.replace(TrackSpline.fromPoints(UUID.randomUUID(), "world", false,
				List.of(new double[] {8, 64, 20}, new double[] {8, 64, 60})));
		// Start on the stem, end on the spur's free end: the spur grows from its start.
		TrackLayResult laid = registry.lay("world", 0, 64, 110, 8, 64, 60);
		assertEquals(TrackLayResult.Kind.CONNECT, laid.kind);
		TrackJunction back = registry.junctionsOn(stem.getId()).get(0);
		assertEquals(spur.getId(), back.branchSplineId);
		assertTrue(back.atEnd);
	}

	@Test
	void prependingOntoTheLineMeetsItAtTheTracksStart() throws Exception {
		TrackRegistry registry = new TrackRegistry(dir.toFile());
		TrackSpline stem = registry.replace(line(0, 0, 200));
		TrackSpline spur = registry.replace(TrackSpline.fromPoints(UUID.randomUUID(), "world", false,
				List.of(new double[] {8, 64, 60}, new double[] {8, 64, 20})));
		registry.lay("world", 8, 64, 60, 0, 64, 110);
		TrackJunction back = registry.junctionsOn(stem.getId()).get(0);
		assertEquals(spur.getId(), back.branchSplineId);
		assertFalse(back.atEnd);
		assertEquals(0, registry.get(spur.getId()).orElseThrow().first().x, 1e-6);
	}

	@Test
	void movingJunctionsTogetherChecksSpacingAtTheirNewPlaces() throws Exception {
		TrackRegistry registry = new TrackRegistry(dir.toFile());
		TrackSpline stem = registry.replace(line(0, 0, 60));
		TrackJunction a = registry.putJunction(new TrackJunction(UUID.randomUUID(), stem.getId(), 20, 1,
				TrackJunction.Side.LEFT, UUID.randomUUID()));
		TrackJunction b = registry.putJunction(new TrackJunction(UUID.randomUUID(), stem.getId(), 40, 1,
				TrackJunction.Side.LEFT, UUID.randomUUID()));
		registry.lay("world", 0, 64, 0, 0, 64, -20);
		assertEquals(40, registry.getJunction(a.id).orElseThrow().s, 1e-6);
		assertEquals(60, registry.getJunction(b.id).orElseThrow().s, 1e-6);
	}

	@Test
	void junctionsThatEndUpTooCloseAfterAMoveLoseOne() throws Exception {
		TrackRegistry registry = new TrackRegistry(dir.toFile());
		TrackSpline left = registry.replace(line(0, 0, 40));
		TrackSpline right = registry.replace(line(0, 90, 50));
		TrackJunction a = registry.putJunction(new TrackJunction(UUID.randomUUID(), left.getId(), 38, 1,
				TrackJunction.Side.LEFT, UUID.randomUUID()));
		TrackJunction b = registry.putJunction(new TrackJunction(UUID.randomUUID(), right.getId(), 38, 1,
				TrackJunction.Side.LEFT, UUID.randomUUID()));
		registry.lay("world", 0, 64, 40, 0, 64, 50);
		assertEquals(1, registry.junctionsOn(left.getId()).size());
		assertTrue(registry.getJunction(a.id).isEmpty() != registry.getJunction(b.id).isEmpty());
	}

	@Test
	void theFarEndIsSavedAndLoaded() throws Exception {
		TrackJunction junction = new TrackJunction(UUID.randomUUID(), UUID.randomUUID(), 12, -1,
				TrackJunction.Side.RIGHT, UUID.randomUUID(), true, 9, true);
		JSONObject json = junction.toJson();
		assertEquals(true, json.get("branchEnd"));
		TrackJunction loaded = TrackJunction.fromJson(json);
		assertTrue(loaded.atEnd);
		assertEquals(9, loaded.turnoutEndS, 1e-9);
		assertEquals(30, loaded.branchFrogS(30), 1e-9);
		assertEquals(5, loaded.fromFrog(25, 30), 1e-9);
		assertEquals(1, loaded.crossingSign());
		TrackJunction start = loaded.withAtEnd(false);
		assertFalse(start.toJson().containsKey("branchEnd"));
		assertFalse(TrackJunction.fromJson(start.toJson()).atEnd);
		assertEquals(0, start.branchFrogS(30), 1e-9);
		assertEquals(25, start.fromFrog(25, 30), 1e-9);
		assertEquals(-1, start.crossingSign());
	}
}
