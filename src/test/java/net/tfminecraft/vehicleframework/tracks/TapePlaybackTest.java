package net.tfminecraft.vehicleframework.tracks;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class TapePlaybackTest {
	private static final double LENGTH = 100;

	// A shuttle: out along +s from 10 to the end at 50, a stop there, back to 10, a stop.
	private static ThrottleTape shuttle() {
		List<ThrottleTape.Sample> samples = new ArrayList<>();
		samples.add(new ThrottleTape.Sample(10, 1, 0, 60, "line", null, 1));
		for (int s = 11; s <= 49; s++) {
			samples.add(new ThrottleTape.Sample(s, 1, 60, 2, "line", null, 1));
		}
		samples.add(new ThrottleTape.Sample(50, 1, 0, 80, "line", null, 1));
		samples.add(new ThrottleTape.Sample(50, 1, -1, 1, "line", null, 1));
		samples.add(new ThrottleTape.Sample(50, 1, -2, 1, "line", null, 1));
		for (int s = 49; s >= 11; s--) {
			samples.add(new ThrottleTape.Sample(s, -1, -60, 2, "line", null, 1));
		}
		return new ThrottleTape("line", samples);
	}

	private static TapePlayback.Step step(TapePlayback playback, ThrottleTape tape, double s, int sign) {
		return playback.step(tape, "line", s, LENGTH, false, sign, 1, 40);
	}

	@Test
	void aShuttleTellsTheWayOutFromTheWayBackOverTheSameRails() {
		ThrottleTape tape = shuttle();
		TapePlayback playback = new TapePlayback();
		// Waiting at the start: the stop holds the throttle at zero for its recorded ticks.
		TapePlayback.Step first = step(playback, tape, 10, 1);
		assertEquals(0, first.target());
		assertTrue(first.atStop());
		assertFalse(first.offRoute());
		for (int i = 1; i < 60; i++) {
			assertEquals(0, step(playback, tape, 10, 1).target());
		}
		assertEquals(60, step(playback, tape, 10, 1).target());
		// Out at s 30: forward throttle.
		TapePlayback.Step out = step(playback, tape, 30, 1);
		assertEquals(60, out.target());
		int outCursor = out.cursor();
		// At the far end the stop, then the reverse ramp one tick each, then back.
		TapePlayback.Step end = step(playback, tape, 50, 1);
		assertTrue(end.atStop());
		for (int i = 1; i < 80; i++) {
			step(playback, tape, 50, 1);
		}
		assertEquals(-1, step(playback, tape, 50, 1).target());
		assertEquals(-2, step(playback, tape, 50, 1).target());
		// Still at the end: the reverse throttle holds until the train reaches the next place.
		assertEquals(-2, step(playback, tape, 50, -1).target());
		assertEquals(-60, step(playback, tape, 49.2, -1).target());
		// Back at s 30 on the way back: reverse throttle, a later cursor than on the way out.
		TapePlayback.Step back = step(playback, tape, 30, -1);
		assertEquals(-60, back.target());
		assertTrue(back.cursor() > outCursor);
		assertEquals(back.cursor(), playback.getCursor());
	}

	@Test
	void theEndOfTheTapeRunsOnIntoItsStart() {
		ThrottleTape tape = shuttle();
		TapePlayback playback = new TapePlayback();
		playback.setCursor(tape.size() - 1, 0);
		// The last sample (s 11, going back) leads to the first (s 10): reached at s 10.
		assertEquals(-60, step(playback, tape, 10.9, -1).target());
		TapePlayback.Step start = step(playback, tape, 10, -1);
		assertEquals(0, start.cursor());
		assertTrue(start.atStop());
	}

	@Test
	void aTrainFarFromItsCursorFindsItsPlaceAgain() {
		ThrottleTape tape = shuttle();
		TapePlayback playback = new TapePlayback();
		playback.setCursor(5, 3);
		assertEquals(3, playback.getTicksAt());
		// Driven by hand to s 40 heading back: the way-back sample there.
		TapePlayback.Step step = step(playback, tape, 40, -1);
		assertEquals(-60, step.target());
		assertEquals(-1, tape.sample(step.cursor()).sign);
		playback.setCursor(-1, -5);
		assertEquals(0, playback.getTicksAt());
		assertEquals(60, step(playback, tape, 20, 1).target());
		playback.setCursor(tape.size() + 3, 0);
		assertEquals(60, step(playback, tape, 20, 1).target());
	}

	@Test
	void offTheTapesTracksTheTrainWaits() {
		ThrottleTape tape = shuttle();
		TapePlayback playback = new TapePlayback();
		TapePlayback.Step lost = playback.step(tape, "elsewhere", 10, LENGTH, false, 1, 1, 40);
		assertTrue(lost.offRoute());
		assertEquals(0, lost.target());
		// It looks again only after a while, so a train left off its tape costs little.
		for (int i = 0; i < TapePlayback.RETRY_TICKS; i++) {
			assertTrue(step(playback, tape, 10, 1).offRoute());
		}
		assertFalse(step(playback, tape, 10, 1).offRoute());
		assertTrue(step(playback, tape, 90, 1).offRoute());
		assertTrue(playback.step(null, "line", 10, LENGTH, false, 1, 1, 40).offRoute());
		assertTrue(playback.step(new ThrottleTape("line"), "line", 10, LENGTH, false, 1, 1, 40).offRoute());
		assertTrue(playback.step(tape, null, 10, LENGTH, false, 1, 1, 40).offRoute());
		playback.reset();
		assertEquals(-1, playback.getCursor());
	}

	@Test
	void crossingOntoTheNextTrackMovesTheCursorOn() {
		ThrottleTape tape = new ThrottleTape("stem", List.of(
				new ThrottleTape.Sample(40, 1, 50, 2, "stem", null, 1),
				new ThrottleTape.Sample(49, 1, 50, 2, "stem", null, 1),
				new ThrottleTape.Sample(1, -1, 50, 2, "branch", "j", -1),
				new ThrottleTape.Sample(5, -1, 50, 2, "branch", "j", -1)));
		TapePlayback playback = new TapePlayback();
		playback.setCursor(1, 0);
		// Still on the stem past the last stem sample: the next sample is on the branch.
		assertEquals(50, playback.step(tape, "stem", 49.5, LENGTH, false, 1, 1, 40).target());
		assertEquals(1, playback.getCursor());
		// On the branch, facing the other way: the body-frame throttle converts.
		TapePlayback.Step branch = playback.step(tape, "branch", 0.5, 30, false, -1, -1, 40);
		assertEquals(2, branch.cursor());
		assertEquals(50, branch.target());
		// On the branch with the cursor still on the stem sample: not lost, the next is here.
		playback.setCursor(1, 0);
		assertEquals(2, playback.step(tape, "branch", 0.5, 30, false, -1, -1, 40).cursor());
		// A cursor on the branch while the train is back on an unrelated part of the stem.
		playback.setCursor(2, 0);
		assertEquals(0, playback.step(tape, "stem", 40, LENGTH, false, 1, 1, 40).cursor());
	}

	// A route recorded from a stop at s 90, finished by passing s 88 under power.
	private static ThrottleTape fromAStation() {
		List<ThrottleTape.Sample> samples = new ArrayList<>();
		samples.add(new ThrottleTape.Sample(90, 1, 0, 60, "line", null, 1));
		for (int s = 91; s <= 140; s++) {
			samples.add(new ThrottleTape.Sample(s, 1, 40, 3, "line", null, 1));
		}
		samples.add(new ThrottleTape.Sample(140, 1, 0, 40, "line", null, 1));
		for (int s = 139; s >= 60; s--) {
			samples.add(new ThrottleTape.Sample(s, -1, -40, 3, "line", null, 1));
		}
		samples.add(new ThrottleTape.Sample(60, 1, 0, 40, "line", null, 1));
		for (int s = 61; s <= 88; s++) {
			samples.add(new ThrottleTape.Sample(s, 1, 40, 3, "line", null, 1));
		}
		return new ThrottleTape("line", samples);
	}

	@Test
	void runningFromTheEndOfTheTapeIntoItsStartingStopBrakesToStopThere() {
		ThrottleTape tape = fromAStation();
		TapePlayback playback = new TapePlayback();
		// One throttle point is 0.0072 blocks a tick, as the locomotive at 0.72.
		double perThrottle = 0.0072;
		// Far from the stop: the recorded throttle.
		assertEquals(40, playback.step(tape, "line", 75, 200, false, 1, 1, 40, perThrottle).target());
		// Close to it: only as much as still stops there, at least 1 until it is reached.
		int near = playback.step(tape, "line", 88.5, 200, false, 1, 1, 40, perThrottle).target();
		assertTrue(near < 40 && near >= 1, "braking throttle " + near);
		assertEquals(15, playback.step(tape, "line", 89.6, 200, false, 1, 1, 40, perThrottle).target());
		// At the stop: it stands, and it is a stop docks serve.
		TapePlayback.Step stop = playback.step(tape, "line", 89.8, 200, false, 1, 1, 40, perThrottle);
		assertEquals(0, stop.cursor());
		assertTrue(stop.atStop());
		// Simulated with the throttle coming down a point a tick: it stops within half a block.
		TapePlayback run = new TapePlayback();
		double s = 80;
		int throttle = 40;
		for (int tick = 0; tick < 400 && run.getCursor() != 0; tick++) {
			int target = run.step(tape, "line", s, 200, false, 1, 1, 40, perThrottle).target();
			throttle += Integer.signum(target - throttle);
			s += throttle * perThrottle;
		}
		assertEquals(0, run.getCursor());
		for (int tick = 0; tick < 50; tick++) {
			int target = run.step(tape, "line", s, 200, false, 1, 1, 40, perThrottle).target();
			throttle += Integer.signum(target - throttle);
			s += throttle * perThrottle;
		}
		assertEquals(90, s, 0.8);
	}

	@Test
	void brakingLooksOnlyAtStopsAheadOnThisTrack() {
		assertEquals(Integer.MAX_VALUE, TapePlayback.brakingThrottle(Double.POSITIVE_INFINITY, 0.0072));
		assertEquals(0, TapePlayback.brakingThrottle(0.1, 1));
		assertEquals(1, TapePlayback.brakingThrottle(0.4, 1));
		assertEquals(25, TapePlayback.brakingThrottle(2, 0.0072));
		// The next stop is on another track, or too far: no braking.
		ThrottleTape tape = new ThrottleTape("line", List.of(new ThrottleTape.Sample(10, 1, 40, 3, "line", null, 1),
				new ThrottleTape.Sample(11, 1, 40, 3, "line", null, 1),
				new ThrottleTape.Sample(12, 1, 0, 50, "other", null, 1)));
		assertEquals(40, new TapePlayback().step(tape, "line", 10, 200, false, 1, 1, 40, 0.0072).target());
		ThrottleTape far = new ThrottleTape("line", List.of(new ThrottleTape.Sample(10, 1, 40, 3, "line", null, 1),
				new ThrottleTape.Sample(11, 1, 40, 3, "line", null, 1),
				new ThrottleTape.Sample(150, 1, 0, 50, "line", null, 1)));
		assertEquals(40, new TapePlayback().step(far, "line", 10, 200, false, 1, 1, 40, 0.0072).target());
	}

	@Test
	void aStopShorterThanTheDockMinimumIsNotAStop() {
		ThrottleTape tape = new ThrottleTape("loop", List.of(
				new ThrottleTape.Sample(10, 1, 0, 10, "loop", null, 1),
				new ThrottleTape.Sample(10, 1, 30, 1, "loop", null, 1),
				new ThrottleTape.Sample(60, 1, 30, 1, "loop", null, 1)));
		TapePlayback playback = new TapePlayback();
		assertFalse(playback.step(tape, "loop", 10, LENGTH, true, 1, 1, 40).atStop());
		assertTrue(new TapePlayback().step(tape, "loop", 10, LENGTH, true, 1, 1, 0).atStop());
		// Round the loop's seam: s 95 to s 5 is 10 blocks on, the short way.
		ThrottleTape seam = new ThrottleTape("loop", List.of(
				new ThrottleTape.Sample(95, 1, 30, 1, "loop", null, 1),
				new ThrottleTape.Sample(5, 1, 30, 1, "loop", null, 1),
				new ThrottleTape.Sample(50, 1, 30, 1, "loop", null, 1)));
		TapePlayback round = new TapePlayback();
		round.setCursor(0, 0);
		assertEquals(0, round.step(seam, "loop", 99, LENGTH, true, 1, 1, 40).cursor());
		assertEquals(1, round.step(seam, "loop", 4.8, LENGTH, true, 1, 1, 40).cursor());
	}
}
