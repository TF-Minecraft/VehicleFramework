package net.tfminecraft.vehicleframework.tracks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class TrackCurveTest {

	@Test
	void tooShort_failsWithRemaining() {
		TrackLayException ex = assertThrows(TrackLayException.class, () -> TrackCurve.between(
				0, 64, 0, 0, 64, 3, 8, 10, 15, 1.0));
		assertTrue(ex.getMessage().contains("8.0"));
		assertTrue(ex.getMessage().contains("3.0"));
	}

	@Test
	void twentyDegreeTurn_failsFifteenCap() {
		double[] end = endOnArc(0, 0, 0f, 20, 12);
		assertThrows(TrackLayException.class, () -> TrackCurve.lay(
				0, 64, 0, 0f, end[0], 64, end[1], 8, 15, 1.0));
	}

	@Test
	void shallowTurn_yawIncreases() throws TrackLayException {
		double[] end = endOnArc(0, 0, 0f, 10, 12);
		List<double[]> points = TrackCurve.lay(
				0, 64, 0, 0f, end[0], 64, end[1], 8, 15, 1.0);
		assertTrue(points.size() >= 8);
		float prev = yaw(points.get(0), points.get(1));
		for (int i = 1; i < points.size() - 1; i++) {
			float next = yaw(points.get(i), points.get(i + 1));
			assertTrue(next >= prev - 0.5f, "yaw should not jump back");
			prev = next;
		}
	}

	@Test
	void straightPlusZ_southYaw() throws TrackLayException {
		List<double[]> points = TrackCurve.between(0, 64, 0, 0, 64, 10, 8, 10, 15, 1.0);
		assertEquals(11, points.size());
		assertEquals(0, points.get(5)[0], 1e-6);
		assertEquals(5, points.get(5)[2], 1e-6);
		assertEquals(64, points.get(5)[1], 1e-6);
	}

	@Test
	void straightRise_staysFlatThenClimbs() throws TrackLayException {
		List<double[]> points = TrackCurve.between(0, 64, 0, 0, 66, 20, 8, 10, 15, 1.0);
		assertEquals(64, points.get(0)[1], 1e-6);
		assertEquals(66, points.get(points.size() - 1)[1], 1e-6);
		assertEquals(64, points.get(4)[1], 0.15);
	}

	@Test
	void between_eastChord_doesNotUseFacing() throws TrackLayException {
		List<double[]> points = TrackCurve.between(0, 161, 0, 61, 162, -2, 8, 15, 20, 1.0);
		assertEquals(61, points.get(points.size() - 1)[0], 1e-6);
		assertEquals(-2, points.get(points.size() - 1)[2], 1e-6);
	}

	@Test
	void aligned_offGridHeadingAlongRow_endsOnRowHeading() throws TrackLayException {
		// Main line: the rail heads 5 degrees off a row of block centres and each
		// click along the row used to bend it 5 degrees off the other way.
		List<double[]> points = TrackCurve.layAligned(
				0.5, 64, 0.5, 95f, -11.5, 64, 0.5, 8, 35, 6, 10, 1.0);
		double[] end = points.get(points.size() - 1);
		assertEquals(-11.5, end[0], 1e-6);
		assertEquals(0.5, end[2], 1e-6);
		assertEquals(90f, endYaw(points), 0.05f);
		for (double[] p : points) {
			assertEquals(0.5, p[2], 0.25);
		}
	}

	@Test
	void aligned_onGridRow_staysStraight() throws TrackLayException {
		List<double[]> points = TrackCurve.layAligned(
				0.5, 64, 0.5, 90f, -11.5, 64, 0.5, 8, 35, 6, 10, 1.0);
		for (double[] p : points) {
			assertEquals(0.5, p[2], 1e-9);
		}
	}

	@Test
	void aligned_nearDiagonal_turnsThenRunsOnDiagonal() throws TrackLayException {
		// A plain arc to this click would end at about 40 degrees; the track
		// should finish on the 45 degree diagonal instead.
		List<double[]> points = TrackCurve.layAligned(
				0.5, 64, 0.5, 20f, -12.5, 64, 18.5, 8, 35, 6, 10, 1.0);
		assertEquals(45f, endYaw(points), 0.05f);
		double[] end = points.get(points.size() - 1);
		assertEquals(-12.5, end[0], 1e-6);
		assertEquals(18.5, end[2], 1e-6);
		assertTrue(minRadius(points) >= TrackCurve.minRadius(8, 35) - 0.5);
	}

	@Test
	void aligned_clickBesideOffGridLine_continuesStraight() throws TrackLayException {
		float yaw = -67.38f;
		double rad = Math.toRadians(yaw);
		double bx = Math.floor(0.5 - Math.sin(rad) * 13) + 0.5;
		double bz = Math.floor(0.5 + Math.cos(rad) * 13) + 0.5;
		List<double[]> points = TrackCurve.layAligned(
				0.5, 64, 0.5, yaw, bx, 64, bz, 8, 35, 6, 10, 1.0);
		for (int i = 1; i < points.size(); i++) {
			assertEquals(yaw, yaw(points.get(i - 1), points.get(i)), 0.01f);
		}
		double[] end = points.get(points.size() - 1);
		assertEquals(bx, end[0], 0.5);
		assertEquals(bz, end[2], 0.5);
	}

	@Test
	void aligned_deliberateCurve_isPlainArc() throws TrackLayException {
		double[] end = endOnArc(0, 0, 0f, 25, 12);
		List<double[]> aligned = TrackCurve.layAligned(
				0, 64, 0, 0f, end[0], 64, end[1], 8, 35, 6, 10, 1.0);
		List<double[]> plain = TrackCurve.lay(
				0, 64, 0, 0f, end[0], 64, end[1], 8, 35, 6, 10, 1.0);
		assertEquals(plain.size(), aligned.size());
		for (int i = 0; i < plain.size(); i++) {
			assertEquals(plain.get(i)[0], aligned.get(i)[0], 1e-9);
			assertEquals(plain.get(i)[2], aligned.get(i)[2], 1e-9);
		}
	}

	@Test
	void endYaw_isTangentNotLastChord() throws TrackLayException {
		double[] end = endOnArc(0, 0, 0f, 20, 12);
		List<double[]> points = TrackCurve.lay(0, 64, 0, 0f, end[0], 64, end[1], 8, 35, 1.0);
		assertEquals(20f, endYaw(points), 0.05f);
	}

	private static float endYaw(List<double[]> points) {
		int n = points.size();
		return TrackCurve.endYaw(points.get(n - 3), points.get(n - 2), points.get(n - 1));
	}

	private static double minRadius(List<double[]> points) {
		double min = Double.MAX_VALUE;
		for (int i = 2; i < points.size(); i++) {
			double[] a = points.get(i - 2);
			double[] b = points.get(i - 1);
			double[] c = points.get(i);
			double ab = Math.hypot(b[0] - a[0], b[2] - a[2]);
			double bc = Math.hypot(c[0] - b[0], c[2] - b[2]);
			double ca = Math.hypot(a[0] - c[0], a[2] - c[2]);
			double area2 = Math.abs((b[0] - a[0]) * (c[2] - a[2]) - (b[2] - a[2]) * (c[0] - a[0]));
			if (area2 > 1e-9 && ab > 0.2 && bc > 0.2) {
				min = Math.min(min, ab * bc * ca / (2 * area2));
			}
		}
		return min;
	}

	private static double[] endOnArc(double ax, double az, float startYaw, double turnDeg, double chord) {
		double yawRad = Math.toRadians(startYaw);
		double tx = -Math.sin(yawRad);
		double tz = Math.cos(yawRad);
		double nx = -tz;
		double nz = tx;
		double phi = Math.toRadians(turnDeg);
		double radius = chord / (2.0 * Math.sin(Math.abs(phi) / 2.0));
		double cx = ax + nx * radius;
		double cz = az + nz * radius;
		double pox = ax - cx;
		double poz = az - cz;
		double cos = Math.cos(phi);
		double sin = Math.sin(phi);
		double rx = pox * cos - poz * sin;
		double rz = pox * sin + poz * cos;
		return new double[] {cx + rx, cz + rz};
	}

	private static float yaw(double[] a, double[] b) {
		return (float) Math.toDegrees(Math.atan2(-(b[0] - a[0]), b[2] - a[2]));
	}
}
