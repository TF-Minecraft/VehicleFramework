package net.tfminecraft.vehicleframework.tracks;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TrainRouteTest {

	@TempDir Path directory;

	@ParameterizedTest
	@CsvSource({"1, 1", "1, -1", "-1, 1", "-1, -1"})
	void crossingAndRetracingConservesDistanceAndPhysicalFacing(int facing, int bodyDirection) {
		TrackRegistry registry = network(facing, false);
		TrackJunction junction = registry.junctionsOn(stem).get(0);
		TrainRoute route = new TrainRoute(registry, Map.of(junction.id, true));
		TrainRoute.Position start = new TrainRoute.Position(stem, 50 - facing * 0.1, facing * bodyDirection);
		TrainRoute.Walk crossed = route.walk(start, bodyDirection * 2, false, true);
		assertEquals(branch, crossed.position().splineId());
		assertEquals(1.9, crossed.position().s(), 1e-9);
		assertEquals(bodyDirection, crossed.position().orientation());
		assertEquals(0, crossed.missing());
		TrainRoute.Walk back = route.walk(crossed.position(), -bodyDirection * 2, false, true);
		assertEquals(start.splineId(), back.position().splineId());
		assertEquals(start.s(), back.position().s(), 1e-9);
		assertEquals(start.orientation(), back.position().orientation());
	}

	@Test
	void branchTipIsAnEndAndDoesNotConnectToTheStem() {
		TrackRegistry registry = network(-1, false);
		TrainRoute route = new TrainRoute(registry, Map.of());
		TrainRoute.Walk result = route.walk(new TrainRoute.Position(branch, 29, -1), -5);
		assertEquals(branch, result.position().splineId());
		assertEquals(30, result.position().s());
		assertEquals(4, result.missing());
	}

	@Test
	void chosenThroughRouteWinsOverLaterSwitchChangeAndLoopSeam() {
		TrackRegistry registry = network(-1, true);
		TrackJunction junction = registry.junctionsOn(stem).get(0);
		registry.setThrown(junction.id, true);
		TrainRoute route = new TrainRoute(registry, Map.of(junction.id, false));
		TrainRoute.Walk result = route.walk(new TrainRoute.Position(stem, 51, 1), -52, true, false);
		assertEquals(stem, result.position().splineId());
		assertEquals(registry.get(stem).orElseThrow().length() - 1, result.position().s(), 1e-9);
		assertEquals(false, result.junctions().get(junction.id));
	}

	private UUID stem;
	private UUID branch;

	private TrackRegistry network(int facing, boolean loop) {
		TrackStore store = new TrackStore(directory.toFile());
		stem = UUID.randomUUID();
		branch = UUID.randomUUID();
		store.save(TrackSpline.fromPoints(stem, "world", loop,
				List.of(new double[]{0, 64, 0}, new double[]{0, 64, 100})));
		store.save(TrackSpline.fromPoints(branch, "world", false,
				List.of(new double[]{0, 64, 50}, new double[]{0, 64, 50 + facing * 30})));
		store.saveJunction("world", new TrackJunction(UUID.randomUUID(), stem, 50,
				facing, TrackJunction.Side.LEFT, branch, false));
		TrackRegistry registry = new TrackRegistry(directory.toFile());
		registry.loadFromDisk();
		return registry;
	}
}
