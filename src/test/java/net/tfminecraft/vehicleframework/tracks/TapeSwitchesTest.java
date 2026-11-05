package net.tfminecraft.vehicleframework.tracks;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TapeSwitchesTest {
	@TempDir Path directory;
	private TrackRegistry registry;
	private TrackStore store;
	private TrackSpline stem;
	private TrackSpline branch;
	private TrackSpline other;
	private final Set<UUID> occupied = new HashSet<>();

	@BeforeEach
	void setup() {
		registry = new TrackRegistry(directory.toFile());
		store = new TrackStore(directory.toFile());
		stem = line(0);
		branch = line(10);
		other = line(20);
		registry.loadFromDisk();
		registry.onJunctionOccupied(occupied::contains);
	}

	private TrackSpline line(double x) {
		TrackSpline track = TrackSpline.fromPoints(UUID.randomUUID(), "world", false,
				List.of(new double[] {x, 64, 0}, new double[] {x, 64, 100}));
		store.save(track);
		return track;
	}

	private TrackJunction junction(double at, int facing, TrackSpline to, boolean thrown) {
		TrackJunction junction = new TrackJunction(UUID.randomUUID(), stem.getId(), at, facing,
				TrackJunction.Side.RIGHT, to.getId(), thrown, 3);
		store.saveJunction("world", junction);
		registry.loadFromDisk();
		return junction;
	}

	// Along the stem, then onto the branch.
	private ThrottleTape tape() {
		return new ThrottleTape(stem.getId().toString(), List.of(
				new ThrottleTape.Sample(20, 1, 50, 1, stem.getId().toString(), null, 1),
				new ThrottleTape.Sample(21, 1, 50, 1, stem.getId().toString(), null, 1),
				new ThrottleTape.Sample(1, 1, 50, 1, branch.getId().toString(), null, 1)));
	}

	private boolean thrown(TrackJunction junction) {
		return registry.getJunction(junction.id).orElseThrow().thrown;
	}

	@Test
	void throwsTheSwitchOntoTheTapesNextTrackAndSetsOthersThrough() {
		TrackJunction toBranch = junction(30, 1, branch, false);
		TrackJunction toOther = junction(26, 1, other, true);
		TrackJunction behind = junction(10, 1, other, true);
		TrackJunction facingAway = junction(28, -1, other, true);
		TrackJunction far = junction(80, 1, other, true);
		assertFalse(TapeSwitches.align(registry, tape(), 0, stem, 20, 1, 20, 3, Map.of(), null));
		assertTrue(thrown(toBranch));
		assertFalse(thrown(toOther));
		assertTrue(thrown(behind));
		assertTrue(thrown(facingAway));
		assertTrue(thrown(far));
	}

	@Test
	void waitsShortOfASwitchAnotherTrainHoldsTheWrongWay() {
		TrackJunction toBranch = junction(22, 1, branch, false);
		occupied.add(toBranch.id);
		assertTrue(TapeSwitches.align(registry, tape(), 0, stem, 20, 1, 20, 3, Map.of(), null));
		assertFalse(TapeSwitches.align(registry, tape(), 0, stem, 15, 1, 20, 3, Map.of(), null));
		// This train is on it already: it goes the way its cars are.
		assertFalse(TapeSwitches.align(registry, tape(), 0, stem, 20, 1, 20, 3, Map.of(toBranch.id, false), null));
		assertFalse(thrown(toBranch));
		// Already set the right way: nothing to do.
		occupied.clear();
		TapeSwitches.align(registry, tape(), 0, stem, 20, 1, 20, 3, Map.of(), null);
		assertTrue(thrown(toBranch));
		assertFalse(TapeSwitches.align(registry, tape(), 0, stem, 20, 1, 20, 3, Map.of(), null));
	}

	@Test
	void nothingToAlignWithoutAMovingTrainATapeOrATrack() {
		junction(30, 1, branch, false);
		assertFalse(TapeSwitches.align(null, tape(), 0, stem, 20, 1, 20, 3, Map.of(), null));
		assertFalse(TapeSwitches.align(registry, null, 0, stem, 20, 1, 20, 3, Map.of(), null));
		assertFalse(TapeSwitches.align(registry, new ThrottleTape("x"), 0, stem, 20, 1, 20, 3, Map.of(), null));
		assertFalse(TapeSwitches.align(registry, tape(), 0, null, 20, 1, 20, 3, Map.of(), null));
		assertFalse(TapeSwitches.align(registry, tape(), 0, stem, 20, 0, 20, 3, Map.of(), null));
		// A tape that never leaves this track sets every facing switch through.
		TrackJunction plain = junction(25, 1, branch, true);
		ThrottleTape stays = new ThrottleTape(stem.getId().toString(), List.of(new ThrottleTape.Sample(20, 1, 50)));
		assertFalse(TapeSwitches.align(registry, stays, 0, stem, 20, 1, 20, 3, Map.of(), null));
		assertFalse(thrown(plain));
		// A junction stub with no branch yet is skipped.
		store.saveJunction("world", new TrackJunction(UUID.randomUUID(), stem.getId(), 24, 1,
				TrackJunction.Side.LEFT, null));
		registry.loadFromDisk();
		assertFalse(TapeSwitches.align(registry, tape(), 0, stem, 20, 1, 20, 3, Map.of(), null));
	}
}
