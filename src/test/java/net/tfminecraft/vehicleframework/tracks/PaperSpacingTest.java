package net.tfminecraft.vehicleframework.tracks;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.database.TrackedCar;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.handlers.TrainHandler;

class PaperSpacingTest {
	@TempDir Path directory;
	private TrackRegistry registry;
	private TrackSpline stem, branch, other;

	@BeforeEach
	void setup() {
		registry = new TrackRegistry(directory.toFile());
		TrackStore store = new TrackStore(directory.toFile());
		stem = line(store, 0);
		branch = line(store, 10);
		other = line(store, 40);
		// The branch leaves the stem at s 60 and starts there at its own s 0.
		store.saveJunction("world", new TrackJunction(UUID.randomUUID(), stem.getId(), 60, 1,
				TrackJunction.Side.RIGHT, branch.getId(), true, 3));
		store.saveJunction("world", new TrackJunction(UUID.randomUUID(), stem.getId(), 80, 1,
				TrackJunction.Side.LEFT, null));
		registry.loadFromDisk();
	}

	private static TrackSpline line(TrackStore store, double x) {
		TrackSpline track = TrackSpline.fromPoints(UUID.randomUUID(), "world", false,
				List.of(new double[] {x, 64, 0}, new double[] {x, 64, 100}));
		store.save(track);
		return track;
	}

	private GhostTrain run(String loco, TrackSpline track, double s) {
		GhostTrain ghost = new GhostTrain("world", List.of(loco), List.of("a"), new double[] {0}, new double[] {3},
				null, new TapeTimetable.Clock(0, 0), Map.of(), 1, false, 0, false, 0, null, 0, 0);
		ghost.spots = List.of(spot(track, s));
		return ghost;
	}

	private static GhostTrain.Spot spot(TrackSpline track, double s) {
		return new GhostTrain.Spot(track.getId(), s, 1, 1, track.sampleAt(s));
	}

	@Test
	void distanceRunsAlongTheRailsAndThroughASwitch() {
		PaperSpacing spacing = PaperSpacing.now(registry, List.of(), List.of(), List.of());
		PaperSpacing.Car onBranch = new PaperSpacing.Car("b", branch.getId(), 5, 2, 0);
		PaperSpacing.Car onStem = new PaperSpacing.Car("s", stem.getId(), 70, 2, 0);
		assertEquals(15, spacing.apart(spot(stem, 50), onBranch), 1e-9);
		assertEquals(15, spacing.apart(spot(branch, 5), new PaperSpacing.Car("s", stem.getId(), 50, 2, 0)), 1e-9);
		assertEquals(20, spacing.apart(spot(stem, 50), onStem), 1e-9);
		// No switch joins them, or the branch's own switch does not reach that track.
		assertEquals(Double.POSITIVE_INFINITY, spacing.apart(spot(other, 50), onStem));
		assertEquals(Double.POSITIVE_INFINITY,
				spacing.apart(spot(branch, 5), new PaperSpacing.Car("o", other.getId(), 5, 2, 0)));
		GhostTrain ghost = run("g", stem, 50);
		assertEquals(15 - 3 - 2, spacing.room(ghost, ghost.spots, onBranch), 1e-9);
		// From the branch, a car parked on the stem past the frog: 2 + 3 apart is touching.
		TrackedCar onTheStem = new TrackedCar("stem", stem.getId().toString(), 63, null, "world", 0, 0, 0);
		GhostTrain back = run("b", branch, 10);
		PaperSpacing parked = PaperSpacing.now(registry, List.of(), List.of(onTheStem), List.of(back));
		assertEquals("stem", parked.blocker(back, back.spots, List.of(spot(branch, 2)), List.of(back)));
	}

	@Test
	void aParkedCarPastTheSwitchHoldsATrainOnlyWhenItWouldTouchIt() {
		TrackedCar parked = new TrackedCar("parked", branch.getId().toString(), 5, null, "world", 0, 0, 0);
		TrackedCar elsewhere = new TrackedCar("lost", "not-a-uuid", 5, null, "world", 0, 0, 0);
		GhostTrain ghost = run("g", stem, 40);
		PaperSpacing spacing = PaperSpacing.now(registry, List.of(), List.of(parked, elsewhere), List.of(ghost));
		double reach = Cache.trainCollisionWarningParkedHalfLength;
		// Up to the point where the cars would touch: no gap is kept from a standing train.
		// Through the frog: (60 - s) along the stem plus 5 along the branch.
		double clear = 60 + 5 - 3 - reach;
		assertNull(spacing.blocker(ghost, ghost.spots, List.of(spot(stem, clear)), List.of(ghost)));
		assertEquals("parked", spacing.blocker(ghost, ghost.spots, List.of(spot(stem, clear + 1)), List.of(ghost)));
		// Its own saved row never holds it.
		TrackedCar own = new TrackedCar("G", stem.getId().toString(), 41, null, "world", 0, 0, 0);
		assertNull(PaperSpacing.now(registry, List.of(), List.of(own), List.of(ghost))
				.blocker(ghost, ghost.spots, List.of(spot(stem, 42)), List.of(ghost)));
	}

	@Test
	void anotherRunOnPaperIsKeptAtTheGapAndOneThatStoppedStandsWhereItWas() {
		GhostTrain ghost = run("g", stem, 20);
		GhostTrain ahead = run("ahead", stem, 40);
		PaperSpacing spacing = PaperSpacing.now(registry, List.of(), List.of(), List.of(ghost, ahead));
		double clear = 40 - 3 - 3 - Cache.trainGhostGap;
		assertNull(spacing.blocker(ghost, ghost.spots, List.of(spot(stem, clear)), List.of(ghost, ahead)));
		assertEquals("ahead",
				spacing.blocker(ghost, ghost.spots, List.of(spot(stem, clear + 1)), List.of(ghost, ahead)));
		// It spawned this tick: no longer a run, it stands there and is kept clear of (touching only).
		spacing.stood(ahead);
		assertNull(spacing.blocker(ghost, ghost.spots, List.of(spot(stem, clear + 1)), List.of(ghost)));
		assertEquals("ahead", spacing.blocker(ghost, ghost.spots, List.of(spot(stem, 36)), List.of(ghost)));
	}

	@Test
	void onALoopCarsAcrossTheSeamAndStalePlacesPastTheEndAreSeen() {
		TrackStore store = new TrackStore(directory.toFile());
		TrackSpline loop = TrackSpline.fromPoints(UUID.randomUUID(), "world", true, List.of(new double[] {0, 64, 0},
				new double[] {100, 64, 0}, new double[] {100, 64, 100}, new double[] {0, 64, 100}));
		store.save(loop);
		registry.loadFromDisk();
		double length = registry.get(loop.getId()).orElseThrow().length();
		TrackSpline track = registry.get(loop.getId()).orElseThrow();
		// Parked just before the seam; another saved past the end (the loop was shortened), and one before 0.
		TrackedCar beforeSeam = new TrackedCar("seam", loop.getId().toString(), length - 2, null, "world", 0, 0, 0);
		TrackedCar pastEnd = new TrackedCar("stale", loop.getId().toString(), length + 30, null, "world", 0, 0, 0);
		TrackedCar belowZero = new TrackedCar("under", loop.getId().toString(), -60, null, "world", 0, 0, 0);
		GhostTrain ghost = run("g", track, 15);
		PaperSpacing spacing = PaperSpacing.now(registry, List.of(), List.of(beforeSeam, pastEnd, belowZero),
				List.of(ghost));
		assertEquals("seam", spacing.blocker(ghost, ghost.spots, List.of(spot(track, 5)), List.of(ghost)));
		ghost.spots = List.of(spot(track, 40));
		assertEquals("stale", spacing.blocker(ghost, ghost.spots, List.of(spot(track, 32)), List.of(ghost)));
		ghost.spots = List.of(spot(track, length - 80));
		assertEquals("under", spacing.blocker(ghost, ghost.spots, List.of(spot(track, length - 66)), List.of(ghost)));
		// Near the end of the loop, the window carries on from the start.
		List<PaperSpacing.Window> wrapped = spacing.windows(ghost, List.of(spot(track, length - 2)));
		assertEquals(2, wrapped.size());
		assertEquals(0, wrapped.get(1).lo(), 1e-9);
		// A whole short loop is one window.
		GhostTrain wide = new GhostTrain("world", List.of("w"), List.of("a"), new double[] {0}, new double[] {length},
				null, new TapeTimetable.Clock(0, 0), Map.of(), 1, false, 0, false, 0, null, 0, 0);
		assertEquals(1, spacing.windows(wide, List.of(spot(track, 5))).size());
	}

	@Test
	void aSwitchAtTheBranchEndIsReachedFromTheEndOfTheBranch() {
		TrackStore store = new TrackStore(directory.toFile());
		TrackSpline rejoin = line(store, 70);
		store.saveJunction("world", new TrackJunction(UUID.randomUUID(), stem.getId(), 30, 1, TrackJunction.Side.LEFT,
				rejoin.getId(), true, 3, true));
		registry.loadFromDisk();
		TrackedCar nearFrog = new TrackedCar("end", rejoin.getId().toString(), 95, null, "world", 0, 0, 0);
		GhostTrain ghost = run("g", stem, 10);
		PaperSpacing spacing = PaperSpacing.now(registry, List.of(), List.of(nearFrog), List.of(ghost));
		// Through the frog: (30 - s) on the stem plus (100 - 95) on the branch; they touch under 3 + 5.
		assertNull(spacing.blocker(ghost, ghost.spots, List.of(spot(stem, 27)), List.of(ghost)));
		assertEquals("end", spacing.blocker(ghost, ghost.spots, List.of(spot(stem, 28)), List.of(ghost)));
	}

	@Test
	void loadedTrainsCountWhileOnTrackAndTheirCasingDoesNotMatter() {
		TrainHandler handler = mock(TrainHandler.class);
		when(handler.isBound()).thenReturn(true);
		when(handler.getSplineId()).thenReturn(stem.getId());
		when(handler.getS()).thenReturn(40.0);
		when(handler.trackReach()).thenReturn(3.0);
		ActiveVehicle loaded = mock(ActiveVehicle.class);
		when(loaded.getUUID()).thenReturn("Loaded");
		when(loaded.isTrain()).thenReturn(true);
		when(loaded.getTrainHandler()).thenReturn(handler);
		ActiveVehicle cart = mock(ActiveVehicle.class);
		when(cart.getUUID()).thenReturn("cart");
		ActiveVehicle unbound = mock(ActiveVehicle.class);
		when(unbound.getUUID()).thenReturn("loose");
		when(unbound.isTrain()).thenReturn(true);
		when(unbound.getTrainHandler()).thenReturn(mock(TrainHandler.class));
		// The loaded train's saved row is where it was saved; only the loaded car counts.
		TrackedCar row = new TrackedCar("loaded", stem.getId().toString(), 22, null, "world", 0, 0, 0);
		GhostTrain ghost = run("g", stem, 10);
		PaperSpacing spacing = PaperSpacing.now(registry, List.of(loaded, cart, mock(ActiveVehicle.class), unbound),
				List.of(row), List.of(ghost));
		assertNull(spacing.blocker(ghost, ghost.spots, List.of(spot(stem, 30)), List.of(ghost)));
		assertEquals("loaded", spacing.blocker(ghost, ghost.spots, List.of(spot(stem, 35)), List.of(ghost)));
	}
}
