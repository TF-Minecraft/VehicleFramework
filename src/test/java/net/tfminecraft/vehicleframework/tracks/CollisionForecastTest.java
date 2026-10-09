package net.tfminecraft.vehicleframework.tracks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class CollisionForecastTest {
	private final UUID line = UUID.randomUUID();

	// A 10-block train at the start of the line, at 10 blocks a second.
	private final CollisionForecast.Mover runner = mover(0, 1, 300, 10, 10);

	@Test
	void stoppedTrainAheadIsMetWhenTheNoseReachesIt() {
		CollisionForecast.Contact contact = CollisionForecast.first(runner, mover(100, 1, 10, 10, 0), 30, 2);
		// The nose reaches 2 blocks short of the standing train at 10 + 10t = 98.
		assertEquals(8.8, contact.seconds(), 1e-9);
		assertEquals(CollisionForecast.Kind.STOPPED, contact.kind());
	}

	@Test
	void oncomingTrainClosesAtBothSpeeds() {
		CollisionForecast.Contact contact = CollisionForecast.first(runner, mover(200, -1, 200, 10, 10), 30, 2);
		assertEquals(8.9, contact.seconds(), 1e-9);
		assertEquals(CollisionForecast.Kind.ONCOMING, contact.kind());
	}

	@Test
	void slowerTrainAheadIsCaughtUp() {
		CollisionForecast.Contact contact = CollisionForecast.first(runner, mover(100, 1, 300, 10, 5), 30, 2);
		assertEquals(17.6, contact.seconds(), 1e-9);
		assertEquals(CollisionForecast.Kind.AHEAD, contact.kind());
	}

	@Test
	void noWarningWhenNothingMeetsInTime() {
		assertNull(CollisionForecast.first(runner, mover(100, 1, 300, 10, 20), 30, 2));
		assertNull(CollisionForecast.first(runner, mover(100, 1, 10, 10, 0), 5, 2));
		assertNull(CollisionForecast.first(mover(0, 1, 300, 10, 0), mover(20, 1, 10, 10, 0), 30, 2));
		CollisionForecast.Mover elsewhere = new CollisionForecast.Mover(new TrainPath(List.of(
				new TrainRoute.Piece(UUID.randomUUID(), 0, 1, 10, 0, false, 10)), 10), 0);
		assertNull(CollisionForecast.first(runner, elsewhere, 30, 2));
	}

	@Test
	void trainsStopAtTheEndOfTheirPath() {
		// The runner's path ends at 50, short of the standing train at 60.
		assertNull(CollisionForecast.first(mover(0, 1, 50, 10, 10), mover(60, 1, 10, 10, 0), 30, 2));
	}

	@Test
	void acceleratingTrainsAreRunForwardAtRisingSpeed() {
		CollisionForecast.Mover steady = new CollisionForecast.Mover(null, 10);
		assertEquals(50, steady.distance(5), 1e-9);
		CollisionForecast.Mover capped = new CollisionForecast.Mover(null, 10, 2, 5);
		assertEquals(50, capped.distance(5), 1e-9);
		CollisionForecast.Mover rising = new CollisionForecast.Mover(null, 10, 2, 14);
		// Two seconds to reach 14, covering 24 blocks, then 14 a second.
		assertEquals(10 * 1 + 1, rising.distance(1), 1e-9);
		assertEquals(24 + 14 * 3, rising.distance(5), 1e-9);
		assertFalse(new CollisionForecast.Mover(null, 0, 1, 10).standing());
		assertTrue(new CollisionForecast.Mover(null, 0).standing());

		// From a standstill, a train opening the throttle still gets a warning.
		CollisionForecast.Mover starting = new CollisionForecast.Mover(path(0, 1, 300, 10), 0, 2, 14);
		CollisionForecast.Contact contact = CollisionForecast.first(starting, mover(100, 1, 10, 10, 0), 30, 2);
		assertEquals(CollisionForecast.Kind.STOPPED, contact.kind());
		assertTrue(contact.seconds() < 8.8 + 2.5 && contact.seconds() > 8.8);
		// An oncoming train that is speeding up is still oncoming, not stopped.
		CollisionForecast.Mover leaving = new CollisionForecast.Mover(path(200, -1, 200, 10), 0, 2, 14);
		assertEquals(CollisionForecast.Kind.ONCOMING, CollisionForecast.first(runner, leaving, 30, 2).kind());
	}

	@Test
	void theTrainBeingCaughtSeesItComingFromBehind() {
		CollisionForecast.Mover slow = mover(100, 1, 300, 10, 5);
		CollisionForecast.Contact contact = CollisionForecast.first(slow, runner, 30, 2);
		assertEquals(CollisionForecast.Kind.BEHIND, contact.kind());
		// A train speeding away from the one behind is also tried at its steady speed.
		CollisionForecast.Mover escaping = new CollisionForecast.Mover(path(100, 1, 300, 10), 5, 10, 30);
		assertEquals(contact.seconds(), CollisionForecast.first(escaping, runner, 30, 2).seconds(), 1e-9);
	}

	@Test
	void carsWithoutALocomotivePassThroughEachOther() {
		CollisionForecast.Mover wagons = new CollisionForecast.Mover(new TrainPath(List.of(
				new TrainRoute.Piece(line, 0, 1, 300, 0, false, 1000)), 10, 1, 0), 10);
		CollisionForecast.Mover parked = new CollisionForecast.Mover(new TrainPath(List.of(
				new TrainRoute.Piece(line, 100, 1, 10, 0, false, 1000)), 10, 1, 0), 0);
		assertNull(CollisionForecast.first(wagons, parked, 30, 2));
		assertEquals(8.8, CollisionForecast.first(wagons, mover(100, 1, 10, 10, 0), 30, 2).seconds(), 1e-9);
	}

	private TrainPath path(double s, int direction, double length, double train) {
		return new TrainPath(List.of(new TrainRoute.Piece(line, s, direction, length, 0, false, 1000)), train);
	}

	private CollisionForecast.Mover mover(double s, int direction, double length, double train, double speed) {
		return new CollisionForecast.Mover(new TrainPath(List.of(
				new TrainRoute.Piece(line, s, direction, length, 0, false, 1000)), train), speed);
	}
}
