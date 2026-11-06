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
