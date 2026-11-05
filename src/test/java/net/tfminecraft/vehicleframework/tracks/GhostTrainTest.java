package net.tfminecraft.vehicleframework.tracks;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GhostTrainTest {
	@TempDir Path directory;
	private TrackRegistry registry;
	private TrackStore store;
	private TrackSpline stem;
	private TrackSpline branch;
	private TrackJunction junction;

	@BeforeEach
	void setup() {
		registry = new TrackRegistry(directory.toFile());
		store = new TrackStore(directory.toFile());
		stem = line(0);
		branch = line(10);
		junction = new TrackJunction(UUID.randomUUID(), stem.getId(), 60, 1, TrackJunction.Side.RIGHT,
				branch.getId(), true, 3);
		store.saveJunction("world", junction);
		store.saveJunction("world", new TrackJunction(UUID.randomUUID(), stem.getId(), 80, 1,
				TrackJunction.Side.LEFT, null));
		registry.loadFromDisk();
	}

	private TrackSpline line(double x) {
		TrackSpline track = TrackSpline.fromPoints(UUID.randomUUID(), "world", false,
				List.of(new double[] {x, 64, 0}, new double[] {x, 64, 100}));
		store.save(track);
		return track;
	}

	// Two seconds on the stem at s 30, then 10 blocks a second, then onto the branch.
	private ThrottleTape tape() {
		String s = stem.getId().toString();
		String b = branch.getId().toString();
		return new ThrottleTape(s, List.of(
				new ThrottleTape.Sample(30, 1, 0, 40, s, null, 1),
				new ThrottleTape.Sample(30, 1, 100, 20, s, null, 1),
				new ThrottleTape.Sample(40, 1, 100, 20, s, null, 1),
				new ThrottleTape.Sample(50, 1, 100, 20, s, null, 1),
				new ThrottleTape.Sample(5, 1, 100, 20, b, junction.id.toString(), 1),
				new ThrottleTape.Sample(15, 1, 100, 20, b, junction.id.toString(), 1)));
	}

	private GhostTrain ghost(double fuel, int reserve) {
		return new GhostTrain("world", List.of("loco", "car"), List.of("a", "a"), new double[] {0, 8},
				new double[] {3, 3}, tape(), new TapeTimetable.Clock(0, 0), Map.of(), fuel, true, 2, true, 50,
				"coal", 10, reserve);
	}

	@Test
	void runsTheTimetableBurningFuelWhileUnderPower() {
		GhostTrain ghost = ghost(45, 2);
		assertEquals("loco", ghost.loco());
		assertEquals(List.of("loco", "car"), ghost.cars());
		// Stood at the stop: the tank tops up from the fuel car, nothing burns.
		assertEquals(List.of(), ghost.run(20, registry));
		assertEquals(50, ghost.fuel, 1e-9);
		assertEquals(1, ghost.reserve);
		assertEquals(1, ghost.consumed);
		ghost.run(20, registry);
		assertEquals(new TapeTimetable.Clock(0, 40), ghost.clock);
		ghost.run(20, registry);
		assertEquals(new TapeTimetable.Clock(1, 20), ghost.clock);
		assertEquals(48, ghost.fuel, 1e-9);
		// Overdrive burns faster.
		GhostTrain fast = new GhostTrain("world", List.of("loco"), List.of("a"), new double[] {0}, new double[] {3},
				new ThrottleTape("t", List.of(new ThrottleTape.Sample(0, 1, 120, 30))), new TapeTimetable.Clock(0, 0),
				Map.of(), 10, true, 2, true, 50, null, 0, 0);
		fast.run(20, registry);
		assertEquals(10 - 2 * 2, fast.fuel, 1e-9);
	}

	@Test
	void outOfFuelItStandsStill() {
		GhostTrain ghost = ghost(0, 0);
		assertTrue(ghost.stalled());
		ghost.run(20, registry);
		assertEquals(new TapeTimetable.Clock(0, 0), ghost.clock);
		GhostTrain dry = ghost(1, 0);
		dry.clock = new TapeTimetable.Clock(1, 0);
		dry.run(20, registry);
		assertEquals(0, dry.fuel, 1e-9);
		assertTrue(dry.stalled());
		GhostTrain free = new GhostTrain("world", List.of("loco"), List.of("a"), new double[] {0}, new double[] {3},
				tape(), new TapeTimetable.Clock(0, 0), Map.of(), 0, false, 0, false, 0, null, 0, 0);
		assertFalse(free.stalled());
		free.run(60, registry);
		assertEquals(0, free.fuel, 1e-9);
	}

	@Test
	void carsFollowTheLocomotiveOntoTheBranchAndForgetSwitchesTheyLeft() {
		GhostTrain ghost = ghost(50, 10);
		ghost.clock = new TapeTimetable.Clock(3, 0);
		List<GhostTrain.Spot> spots = ghost.place(registry);
		assertEquals(50, spots.get(0).s(), 1e-9);
		assertEquals(42, spots.get(1).s(), 1e-9);
		assertEquals(stem.getId(), spots.get(1).splineId());
		assertEquals(1, spots.get(1).sign());
		ghost.run(25, registry);
		assertEquals(new TapeTimetable.Clock(4, 5), ghost.clock);
		assertEquals(Map.of(junction.id, true), ghost.routes);
		spots = ghost.place(registry);
		assertEquals(branch.getId(), spots.get(0).splineId());
		assertSame(spots, ghost.spots);
		ghost.run(60, registry);
		ghost.place(registry);
		assertTrue(ghost.routes.isEmpty());
		// A tape crossing between tracks no switch joins changes nothing.
		ghost.routes.clear();
		ThrottleTape jump = new ThrottleTape("a", List.of(new ThrottleTape.Sample(0, 1, 50, 20, "a", null, 1),
				new ThrottleTape.Sample(0, 1, 50, 20, "b", null, 1)));
		GhostTrain hop = new GhostTrain("world", List.of("loco"), List.of("a"), new double[] {0}, new double[] {3},
				jump, new TapeTimetable.Clock(0, 0), Map.of(), 0, false, 0, false, 0, null, 0, 0);
		hop.run(40, registry);
		hop.run(20, null);
		assertTrue(hop.routes.isEmpty());
		// A track that is gone leaves no place.
		assertNull(hop.place(registry));
	}

	@Test
	void carsPushedAheadTakeTheSwitchTheTapeIsAboutToTake() {
		String s = stem.getId().toString();
		// Facing -s and pushing towards +s: the car is ahead, past the frog at 60.
		ThrottleTape push = new ThrottleTape(s, List.of(
				new ThrottleTape.Sample(55, 1, -40, 20, s, null, -1),
				new ThrottleTape.Sample(59.5, 1, -40, 20, s, null, -1),
				new ThrottleTape.Sample(1, -1, -40, 20, branch.getId().toString(), junction.id.toString(), 1)));
		GhostTrain ghost = new GhostTrain("world", List.of("loco", "car"), List.of("a", "a"), new double[] {0, 8},
				new double[] {3, 3}, push, new TapeTimetable.Clock(0, 0), Map.of(), 0, false, 0, false, 0, null, 0, 0);
		assertEquals(stem.getId(), ghost.place(registry).get(1).splineId());
		ghost.run(1, registry);
		assertEquals(Map.of(junction.id, true), ghost.routes);
		assertEquals(branch.getId(), ghost.place(registry).get(1).splineId());
	}

	@Test
	void aCarWhoseTrackIsGoneLeavesNoPlace() {
		GhostTrain ghost = new GhostTrain("world", List.of("loco", "car"), List.of("a", "a"),
				new double[] {0, 500}, new double[] {3, 3}, tape(), new TapeTimetable.Clock(3, 0),
				Map.of(UUID.randomUUID(), true), 50, true, 2, true, 50, null, 0, 0);
		// The walk back runs off the start of the stem; the place is clamped to the track.
		assertNotNull(ghost.place(registry));
		assertNull(ghost.place(new TrackRegistry(directory.resolve("empty").toFile())));
	}

	@Test
	void theRunSavesAndReadsBack() {
		GhostTrain ghost = ghost(33, 4);
		ghost.clock = new TapeTimetable.Clock(2, 7);
		ghost.routes.put(junction.id, true);
		JSONObject json = ghost.toJson();
		GhostTrain read = GhostTrain.fromJson(json, tape());
		assertEquals("world", read.world);
		assertEquals(List.of("loco", "car"), read.cars);
		assertEquals(List.of("a", "a"), read.owners);
		assertArrayEquals(new double[] {0, 8}, read.offsets);
		assertArrayEquals(new double[] {3, 3}, read.reaches);
		assertEquals(new TapeTimetable.Clock(2, 7), read.clock);
		assertEquals(Map.of(junction.id, true), read.routes);
		assertEquals(33, read.fuel);
		assertTrue(read.useFuel);
		assertEquals(2, read.burn);
		assertTrue(read.locomotive);
		assertEquals(50, read.capacity);
		assertEquals("coal", read.fuelPath);
		assertEquals(10, read.fuelPerItem);
		assertEquals(4, read.reserve);
	}

	@SuppressWarnings("unchecked")
	@Test
	void incompleteRunsAreNotRead() {
		assertNull(GhostTrain.fromJson(null, tape()));
		JSONObject json = ghost(1, 0).toJson();
		assertNull(GhostTrain.fromJson(json, null));
		assertNull(GhostTrain.fromJson(json, new ThrottleTape("t")));
		JSONObject noWorld = (JSONObject) json.clone();
		noWorld.remove("world");
		assertNull(GhostTrain.fromJson(noWorld, tape()));
		JSONObject noCars = (JSONObject) json.clone();
		noCars.put("cars", new JSONArray());
		assertNull(GhostTrain.fromJson(noCars, tape()));
		noCars.put("cars", "loco");
		assertNull(GhostTrain.fromJson(noCars, tape()));
		JSONObject mismatched = (JSONObject) json.clone();
		JSONArray one = new JSONArray();
		one.add(0.0);
		mismatched.put("offsets", one);
		assertNull(GhostTrain.fromJson(mismatched, tape()));
		mismatched.put("offsets", "none");
		assertNull(GhostTrain.fromJson(mismatched, tape()));
		// Older or partial runs fill in defaults.
		JSONObject sparse = new JSONObject();
		JSONArray cars = new JSONArray();
		cars.add("loco");
		cars.add("car");
		sparse.put("cars", cars);
		JSONArray offsets = new JSONArray();
		offsets.add(0L);
		offsets.add("x");
		sparse.put("offsets", offsets);
		sparse.put("world", "world");
		JSONObject routes = new JSONObject();
		routes.put("not-a-uuid", true);
		sparse.put("routes", routes);
		GhostTrain read = GhostTrain.fromJson(sparse, tape());
		assertEquals(List.of("none", "none"), read.owners);
		assertArrayEquals(new double[] {0, 0}, read.offsets);
		assertArrayEquals(new double[] {1, 1}, read.reaches);
		assertTrue(read.routes.isEmpty());
		assertNull(read.fuelPath);
		assertFalse(read.useFuel);
		assertEquals(new TapeTimetable.Clock(0, 0), read.clock);
	}
}
