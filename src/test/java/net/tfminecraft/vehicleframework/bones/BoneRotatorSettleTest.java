package net.tfminecraft.vehicleframework.bones;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

import org.bukkit.entity.Entity;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.ticxo.modelengine.api.model.bone.ModelBone;

import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.util.AccessPanel;

/**
 * A train standing on its track turns its body bone to the same target every tick. Once a
 * turn leaves the bone unchanged, repeating it is skipped; anything else still turns.
 */
class BoneRotatorSettleTest {
	// Float rounding in the turn can creep for a few hundred repeats before it holds still.
	private static final int SETTLE_CALLS = 2000;

	@ParameterizedTest
	@CsvSource({
			"0, 0", "37.3, 2.1", "-91.25, -4.5", "179.99, 0", "180, 1.5", "-180, -1.5",
			"-179.95, 3", "90, 12", "45, -30"})
	void aStandingTrainsTurnSettlesAndIsThenSkipped(float yaw, float pitch) throws Exception {
		BoneRotator rotator = rotator();
		int calls = 0;
		while (!settled(rotator) && calls < SETTLE_CALLS) {
			rotator.rotateToTarget(yaw, pitch, 0f, 1f, true, true, false);
			calls++;
		}
		assertTrue(settled(rotator), "turn to " + yaw + "/" + pitch + " never settled");
		Quaternionf held = new Quaternionf(rotator.getAnimator().getRotation());
		float drive = rotator.getDriveYaw();
		for (int i = 0; i < 3; i++) {
			assertTrue(rotator.rotateToTarget(yaw, pitch, 0f, 1f, true, true, false));
			assertEquals(held, rotator.getAnimator().getRotation());
			assertEquals(drive, rotator.getDriveYaw());
		}
		assertEquals(yaw, angles(rotator).getYaw(), 0.01f);
		assertEquals(pitch, angles(rotator).getPitch(), 0.01f);
	}

	@Test
	void aSkippedTurnMatchesTheTurnItReplaces() throws Exception {
		BoneRotator memo = rotator();
		settle(memo, 37.3f, 2.1f);
		Quaternionf skipped = new Quaternionf(memo.getAnimator().getRotation());
		// The same turn again from the same rotation, run in full on a rotator with no history.
		BoneRotator fresh = rotator();
		fresh.rawSet(skipped.x, skipped.y, skipped.z, skipped.w);
		fresh.rotateToTarget(37.3f, 2.1f, 0f, 1f, true, true, false);
		assertEquals(skipped, fresh.getAnimator().getRotation());
		assertEquals(fresh.getDriveYaw(), memo.getDriveYaw());
	}

	@Test
	void aTurnFromOutsideIsTurnedBackAgain() throws Exception {
		BoneRotator rotator = rotator();
		settle(rotator, 37.3f, 2.1f);
		Quaternionf held = new Quaternionf(rotator.getAnimator().getRotation());
		// Something else turns the bone, keeping the rotator's own driveYaw.
		Quaternionf pushed = new Quaternionf().rotateYXZ((float) Math.toRadians(60), 0, 0);
		rotator.getAnimator().getRotation().set(pushed);
		assertNotEquals(held, rotator.getAnimator().getRotation());

		rotator.rotateToTarget(37.3f, 2.1f, 0f, 1f, true, true, false);

		assertEquals(37.3f, angles(rotator).getYaw(), 0.01f);
		assertEquals(37.3f, rotator.getDriveYaw());
	}

	@Test
	void aResetDriveYawIsSetAgainEvenWhenTheBoneHasNotMoved() throws Exception {
		BoneRotator rotator = rotator();
		settle(rotator, 37.3f, 2.1f);
		Quaternionf held = new Quaternionf(rotator.getAnimator().getRotation());
		// Same rotation, but driveYaw is re-read from it and is no longer exactly the target.
		rotator.rawSet(held.x, held.y, held.z, held.w);
		float reread = rotator.getDriveYaw();

		rotator.rotateToTarget(37.3f, 2.1f, 0f, 1f, true, true, false);

		assertEquals(37.3f, rotator.getDriveYaw());
		assertEquals(held, rotator.getAnimator().getRotation());
		assertTrue(Math.abs(reread - 37.3f) < 0.01f);
	}

	@Test
	void anyOtherTurnIsNotSkipped() throws Exception {
		BoneRotator rotator = rotator();
		settle(rotator, 37.3f, 2.1f);

		assertFalse(rotator.rotateToTarget(80f, 2.1f, 0f, 1f, true, true, false));
		assertEquals(80f, angles(rotator).getYaw(), 0.01f);
		settle(rotator, 37.3f, 2.1f);
		rotator.rotateToTarget(37.3f, 9f, 0f, 1f, true, true, false);
		assertEquals(9f, angles(rotator).getPitch(), 0.01f);
		settle(rotator, 37.3f, 2.1f);
		rotator.rotateToTarget(37.3f, 2.1f, 20f, 1f, true, true, true);
		assertEquals(20f, angles(rotator).getRoll(), 0.01f);
		settle(rotator, 37.3f, 2.1f);
		// Half way from the settled rotation to a new yaw; a different slerp is a different turn.
		rotator.rotateToTarget(37.3f, 2.1f, 0f, 0.5f, true, true, false);
		settle(rotator, 37.3f, 2.1f);
		rotator.rotateToTarget(0f, 2.1f, 0f, 1f, false, true, false);
		assertEquals(37.3f, angles(rotator).getYaw(), 0.01f);
		rotator.rotateToTarget(37.3f, 0f, 0f, 1f, true, false, false);
		assertEquals(2.1f, angles(rotator).getPitch(), 0.01f);
		settle(rotator, 37.3f, 2.1f);
		rotator.rotateToTarget(80f, 2.1f, 0f, 0.5f, true, true, false);
		assertTrue(angles(rotator).getYaw() > 50f && angles(rotator).getYaw() < 70f);
	}

	@Test
	void aMovingTurnIsNeverSkipped() throws Exception {
		BoneRotator rotator = rotator();
		for (int i = 0; i < 20; i++) {
			rotator.rotateToTarget(i * 2f, 1f, 0f, 1f, true, true, false);
			assertEquals(i * 2f, angles(rotator).getYaw(), 0.01f);
			assertEquals(i * 2f, rotator.getDriveYaw());
		}
	}

	private static void settle(BoneRotator rotator, float yaw, float pitch) throws Exception {
		for (int i = 0; i < SETTLE_CALLS && !settled(rotator); i++) {
			rotator.rotateToTarget(yaw, pitch, 0f, 1f, true, true, false);
		}
		rotator.rotateToTarget(yaw, pitch, 0f, 1f, true, true, false);
		assertTrue(settled(rotator));
	}

	private static boolean settled(BoneRotator rotator) throws Exception {
		Field field = BoneRotator.class.getDeclaredField("settled");
		field.setAccessible(true);
		return field.getBoolean(rotator);
	}

	private static ConvertedAngle angles(BoneRotator rotator) {
		return new ConvertedAngle(rotator.getAnimator().getRotation());
	}

	private static BoneRotator rotator() {
		ActiveVehicle vehicle = mock(ActiveVehicle.class);
		when(vehicle.getAccessPanel()).thenReturn(new AccessPanel());
		ModelBone bone = mock(ModelBone.class, RETURNS_DEEP_STUBS);
		when(bone.getBoneId()).thenReturn("body_controller");
		return new BoneRotator(vehicle, mock(Entity.class), bone, new RotationLimits());
	}
}
