package net.tfminecraft.vehicleframework.tracks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.enums.VehicleDeath;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.component.propulsion.Throttle;
import net.tfminecraft.vehicleframework.vehicles.handlers.SeatHandler;
import net.tfminecraft.vehicleframework.vehicles.handlers.TrainHandler;
import net.tfminecraft.vehicleframework.vehicles.util.AccessPanel;

class TrainCollisionTest {
	private static final UUID SPLINE = UUID.randomUUID();
	private final World world = mock(World.class);
	private double explodeSpeed;

	@BeforeEach
	void reset() {
		explodeSpeed = Cache.trainCollisionExplodeSpeed;
		Cache.trainCollisionExplodeSpeed = 3;
		TrainCollision.tick(List.of());
	}

	@AfterEach
	void restore() {
		Cache.trainCollisionExplodeSpeed = explodeSpeed;
	}

	@Test
	void consistHead_liveParentWins() {
		assertEquals("loco", TrainCollision.consistHead("car", "loco", null));
	}

	@Test
	void consistHead_pendingParentWhenUnlinked() {
		assertEquals("loco", TrainCollision.consistHead("car", null, "loco"));
	}

	@Test
	void consistHead_selfWhenAlone() {
		assertEquals("loco", TrainCollision.consistHead("loco", null, null));
	}

	@Test
	void sameConsist_linkedCarAndLoco() {
		assertTrue(TrainCollision.sameConsistIds("car", "loco", null, "loco", null, null));
	}

	@Test
	void sameConsist_pendingAfterUnload() {
		assertTrue(TrainCollision.sameConsistIds("car", null, "loco", "loco", null, null));
	}

	@Test
	void differentConsists() {
		assertFalse(TrainCollision.sameConsistIds("loco-a", null, null, "loco-b", null, null));
	}

	@Test
	void closingSpeed_headOnAddsBothSpeeds() {
		assertEquals(0.3, TrainCollision.closingSpeed(
				new Vector(0, 0, 0), new Vector(0.1, 0, 0), new Vector(5, 0, 0), new Vector(-0.2, 0, 0)), 1e-9);
	}

	@Test
	void closingSpeed_catchingUpIsTheDifference() {
		assertEquals(0.1, TrainCollision.closingSpeed(
				new Vector(0, 0, 0), new Vector(0.3, 0, 0), new Vector(5, 0, 0), new Vector(0.2, 0, 0)), 1e-9);
	}

	@Test
	void closingSpeed_negativeWhilePartingAndRelativeWhenOnTop() {
		assertEquals(-0.1, TrainCollision.closingSpeed(
				new Vector(0, 0, 0), new Vector(-0.1, 0, 0), new Vector(5, 0, 0), new Vector()), 1e-9);
		assertEquals(0.5, TrainCollision.closingSpeed(
				new Vector(1, 0, 1), new Vector(0.3, 0, 0.4), new Vector(1, 0, 1), new Vector()), 1e-9);
	}

	@Test
	void towards_onlyWhenMovingAtTheOther() {
		assertTrue(TrainCollision.towards(new Vector(0, 0, 1), new Vector(), new Vector(0, 0, 3)));
		assertFalse(TrainCollision.towards(new Vector(0, 0, -1), new Vector(), new Vector(0, 0, 3)));
		assertFalse(TrainCollision.towards(new Vector(), new Vector(), new Vector(0, 0, 3)));
	}

	@Test
	void slowContactStopsTheMovingTrainWithoutExploding() {
		Car mover = new Car(0, 0.0072);
		Car parked = new Car(4, 0);
		TrainCollision.tick(List.of(mover.v, parked.v));
		mover.moveTo(3.5);
		Player captain = mock(Player.class);
		when(mover.seats.captainPlayer()).thenReturn(captain);
		TrainCollision.tick(List.of(mover.v, parked.v));

		verify(mover.v, never()).kill(any());
		verify(parked.v, never()).kill(any());
		assertEquals(0, mover.throttle.getCurrent());
		verify(mover.panel).setSpeed(0);
		verify(parked.panel, never()).setSpeed(0);
		verify(world).playSound(any(Location.class), eq(TrainCollision.BUMP_SOUND), anyFloat(), anyFloat());
		verify(captain).sendMessage(anyString());
	}

	@Test
	void creepingIntoATrainKeepsStoppingButTellsOnce() {
		Car mover = new Car(0, 0.0072);
		Car parked = new Car(4, 0);
		Player captain = mock(Player.class);
		when(mover.seats.captainPlayer()).thenReturn(captain);
		TrainCollision.tick(List.of(mover.v, parked.v));
		mover.moveTo(3.5);
		TrainCollision.tick(List.of(mover.v, parked.v));
		mover.moveTo(3.6);
		TrainCollision.tick(List.of(mover.v, parked.v));

		verify(mover.panel, times(2)).setSpeed(0);
		verify(captain, times(1)).sendMessage(anyString());
	}

	@Test
	void pushingACarSlowlyIntoALocomotiveStopsThePushingTrain() {
		Car standing = new Car(4, 0);
		Car pusher = new Car(-8, 0.0072);
		Car pushed = new Car(0, 0);
		when(pushed.v.hasParent()).thenReturn(true);
		when(pushed.v.getParent()).thenReturn(pusher.v);
		TrainCollision.tick(List.of(standing.v, pusher.v, pushed.v));
		pusher.moveTo(-7.5);
		pushed.moveTo(3.5);
		TrainCollision.tick(List.of(standing.v, pusher.v, pushed.v));

		verify(standing.v, never()).kill(any());
		verify(pushed.v, never()).kill(any());
		assertEquals(0, pusher.throttle.getCurrent());
		verify(pusher.panel).setSpeed(0);
		verify(standing.panel, never()).setSpeed(0);
	}

	@Test
	void backingAwayFromATouchingTrainIsAllowed() {
		Car mover = new Car(3.5, 0.0072);
		Car parked = new Car(4, 0);
		TrainCollision.tick(List.of(mover.v, parked.v));
		mover.moveTo(3.4);
		TrainCollision.tick(List.of(mover.v, parked.v));

		verify(mover.v, never()).kill(any());
		verify(mover.panel, never()).setSpeed(0);
		assertEquals(1, mover.throttle.getCurrent());
	}

	@Test
	void fastContactExplodesBoth() {
		Car mover = new Car(0, 0.72);
		Car parked = new Car(4, 0);
		TrainCollision.tick(List.of(mover.v, parked.v));
		mover.moveTo(3.5);
		TrainCollision.tick(List.of(mover.v, parked.v));

		verify(mover.v).kill(VehicleDeath.EXPLODE);
		verify(parked.v).kill(VehicleDeath.EXPLODE);
	}

	@Test
	void zeroExplodeSpeedExplodesOnAnyContact() {
		Cache.trainCollisionExplodeSpeed = 0;
		Car mover = new Car(3.5, 0);
		Car parked = new Car(4, 0);
		TrainCollision.tick(List.of(mover.v, parked.v));

		verify(mover.v).kill(VehicleDeath.EXPLODE);
	}

	private final class Car {
		final ActiveVehicle v = mock(ActiveVehicle.class);
		final AccessPanel panel = mock(AccessPanel.class);
		final SeatHandler seats = mock(SeatHandler.class);
		final Throttle throttle = new Throttle("Throttle", 120, -100, null);
		private Location at;

		Car(double x, double speed) {
			at = new Location(world, x, 64, 0);
			Entity entity = mock(Entity.class);
			TrainHandler train = mock(TrainHandler.class);
			when(entity.getWorld()).thenReturn(world);
			when(entity.getLocation()).thenAnswer(call -> at.clone());
			when(entity.getBoundingBox()).thenAnswer(call -> new BoundingBox(
					at.getX() - 0.5, at.getY(), at.getZ() - 0.5, at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5));
			when(train.isBound()).thenReturn(true);
			when(train.getSplineId()).thenReturn(SPLINE);
			when(panel.getSpeed()).thenReturn(speed);
			when(v.getUUID()).thenReturn(UUID.randomUUID().toString());
			when(v.isTrain()).thenReturn(true);
			when(v.getEntity()).thenReturn(entity);
			when(v.getTrainHandler()).thenReturn(train);
			when(v.getAccessPanel()).thenReturn(panel);
			when(v.getThrottle()).thenReturn(throttle);
			when(v.getSeatHandler()).thenReturn(seats);
			when(v.hasDeathData(VehicleDeath.EXPLODE)).thenReturn(true);
			throttle.setThrottle(speed == 0 ? 0 : 1);
		}

		void moveTo(double x) {
			at = new Location(world, x, 64, 0);
		}
	}
}
