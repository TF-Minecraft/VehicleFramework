package net.tfminecraft.vehicleframework.bones;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.joml.Quaternionf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ConvertedAngleTest {

	@ParameterizedTest
	@CsvSource({
		"0, 0, 0",
		"90, -10, 0",
		"90, -10, 20",
		"30, -20, 30",
		"135, -15, -40",
		"-60, -60, 0",
		"90, -30, 60",
		"-170, 45, -80"
	})
	void decodesRotateYxz(float yaw, float pitch, float roll) {
		Quaternionf q = new Quaternionf().rotateYXZ(
				(float) Math.toRadians(yaw),
				(float) Math.toRadians(pitch),
				(float) Math.toRadians(roll));

		ConvertedAngle a = new ConvertedAngle(q);

		assertEquals(yaw, a.getYaw(), 0.01f);
		assertEquals(pitch, a.getPitch(), 0.01f);
		assertEquals(roll, a.getRoll(), 0.01f);
	}
}
