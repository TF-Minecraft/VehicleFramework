package net.tfminecraft.vehicleframework.bones;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

import com.ticxo.modelengine.api.model.bone.ModelBone;

import net.tfminecraft.vehicleframework.enums.SeatType;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.controller.RotateController;
import net.tfminecraft.vehicleframework.vehicles.seat.Seat;
import net.tfminecraft.vehicleframework.vehicles.util.AccessPanel;

class BoneRotatorDriveYawTest {

	@Test
	void restoredHeadingIsWhereTurningStarts() {
		Fixture f = new Fixture();
		Quaternionf saved = yaw(170f);
		f.rotator.rawSet(saved.x, saved.y, saved.z, saved.w);

		new RotateController().turnLeft(f.rotator, f.vehicle, f.captain);

		assertEquals(171.2f, boneYaw(f.rotator), 0.01f);
		assertEquals(171.2f, f.rotator.getDriveYaw(), 0.01f);
	}

	@Test
	void resetHeadingIsWhereTurningStarts() {
		Fixture f = new Fixture();
		f.rotator.setRotation(120, 0, 0, true, false, false);
		f.rotator.reset();

		new RotateController().turnRight(f.rotator, f.vehicle, f.captain);

		assertEquals(-1.2f, boneYaw(f.rotator), 0.01f);
	}

	private static Quaternionf yaw(float degrees) {
		return new Quaternionf().rotateYXZ((float) Math.toRadians(degrees), 0, 0);
	}

	private static float boneYaw(BoneRotator rotator) {
		return new ConvertedAngle(rotator.getAnimator().getRotation()).getYaw();
	}

	private static final class Fixture {
		final ActiveVehicle vehicle = mock(ActiveVehicle.class);
		final Player captain = mock(Player.class);
		final BoneRotator rotator;

		Fixture() {
			AccessPanel panel = new AccessPanel();
			panel.setSpeed(0.13);
			panel.setTurnRate(0.24);
			when(vehicle.getAccessPanel()).thenReturn(panel);
			Seat seat = mock(Seat.class);
			when(seat.getType()).thenReturn(SeatType.CAPTAIN);
			when(vehicle.getSeat(captain)).thenReturn(seat);
			ModelBone bone = mock(ModelBone.class, RETURNS_DEEP_STUBS);
			when(bone.getBoneId()).thenReturn("body_controller");
			rotator = new BoneRotator(vehicle, mock(Entity.class), bone, new RotationLimits());
		}
	}
}
