package net.tfminecraft.vehicleframework.tracks;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TapeTimetableTest {
	@TempDir Path directory;

	@Test
	void theClockRunsThroughHeldTicksAndWrapsRoundTheTape() {
		ThrottleTape tape = new ThrottleTape("t", List.of(
				new ThrottleTape.Sample(0, 1, 0, 50),
				new ThrottleTape.Sample(0, 1, 40, 10),
				new ThrottleTape.Sample(5, 1, 80, 5)));
		TapeTimetable.Advance first = TapeTimetable.advance(tape, new TapeTimetable.Clock(0, 0), 20, 40);
		assertEquals(new TapeTimetable.Clock(0, 20), first.clock());
		assertEquals(0, first.maxThrottle());
		assertEquals(List.of(), first.stops());
		TapeTimetable.Advance second = TapeTimetable.advance(tape, new TapeTimetable.Clock(0, 45), 20, 40);
		assertEquals(new TapeTimetable.Clock(2, 5), second.clock());
		assertEquals(80, second.maxThrottle());
		// Round the end into the stop at the start, which is long enough to be a stop.
		TapeTimetable.Advance wrap = TapeTimetable.advance(tape, new TapeTimetable.Clock(2, 3), 4, 40);
		assertEquals(new TapeTimetable.Clock(0, 2), wrap.clock());
		assertEquals(List.of(0), wrap.stops());
		assertEquals(List.of(), TapeTimetable.advance(tape, new TapeTimetable.Clock(-1, -4), 3, 60).stops());
		assertEquals(0, TapeTimetable.advance(tape, new TapeTimetable.Clock(1, 0), -5, 40).maxThrottle());
		TapeTimetable.Clock same = new TapeTimetable.Clock(1, 1);
		assertSame(same, TapeTimetable.advance(null, same, 20, 40).clock());
		assertSame(same, TapeTimetable.advance(new ThrottleTape("t"), same, 20, 40).clock());
	}

	@Test
	void thePlaceIsBetweenTheSampleAndTheNextInProportion() {
		TrackRegistry registry = new TrackRegistry(directory.toFile());
		TrackStore store = new TrackStore(directory.toFile());
		TrackSpline line = TrackSpline.fromPoints(UUID.randomUUID(), "world", false,
				List.of(new double[] {0, 64, 0}, new double[] {0, 64, 100}));
		TrackSpline loop = TrackSpline.fromPoints(UUID.randomUUID(), "world", true,
				List.of(new double[] {0, 64, 0}, new double[] {100, 64, 0}, new double[] {100, 64, 100},
						new double[] {0, 64, 100}));
		store.save(line);
		store.save(loop);
		registry.loadFromDisk();
		String id = line.getId().toString();
		ThrottleTape tape = new ThrottleTape(id, List.of(
				new ThrottleTape.Sample(10, 1, 40, 4, id, null, -1),
				new ThrottleTape.Sample(14, 1, 40, 4, null, null, -1),
				new ThrottleTape.Sample(2, 1, 40, 4, UUID.randomUUID().toString(), null, 1)));
		TapeTimetable.Place half = TapeTimetable.place(tape, new TapeTimetable.Clock(0, 2), registry);
		assertEquals(12, half.s(), 1e-9);
		assertEquals(-1, half.orientation());
		assertEquals(1, half.sign());
		assertEquals(40, half.throttle());
		assertEquals(id, half.splineId());
		// The next sample is on another track: stay at the sample.
		assertEquals(14, TapeTimetable.place(tape, new TapeTimetable.Clock(1, 3), registry).s(), 1e-9);
		// A track that is gone, or no registry: the sample itself.
		assertEquals(2, TapeTimetable.place(tape, new TapeTimetable.Clock(2, 2), registry).s(), 1e-9);
		assertEquals(10, TapeTimetable.place(tape, new TapeTimetable.Clock(0, 2), null).s(), 1e-9);
		// Round a loop's seam.
		double length = loop.length();
		String round = loop.getId().toString();
		ThrottleTape seam = new ThrottleTape(round, List.of(new ThrottleTape.Sample(length - 1, 1, 40, 2),
				new ThrottleTape.Sample(1, 1, 40, 2)));
		assertEquals(0, TapeTimetable.place(seam, new TapeTimetable.Clock(0, 1), registry).s(), 1e-6);
		assertNull(TapeTimetable.track(registry, "not-a-uuid"));
		assertNull(TapeTimetable.track(registry, null));
		assertNull(TapeTimetable.track(null, id));
		assertSame(registry.get(line.getId()).orElseThrow(), TapeTimetable.track(registry, id));
	}
}
