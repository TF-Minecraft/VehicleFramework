package net.tfminecraft.vehicleframework.vehicles.handlers.train;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import net.tfminecraft.vehicleframework.tracks.TrackPose;
import net.tfminecraft.vehicleframework.tracks.TrackSpline;

class BogiesTest {
    // Passenger car bogies, 34 px either side of the middle.
    private static final double FRONT = 34.0 / 16;
    private static final double BACK = -34.0 / 16;

    @Test
    void onStraightLevelTrackTheCarSitsOverItsCentre() {
        TrackSpline line = spline(new double[]{0.5, 64, 0}, new double[]{0.5, 64, 40});
        TrackPose body = Bogies.bodyPose(line, 20, FRONT, BACK);
        TrackPose centre = line.sampleAt(20);
        assertEquals(centre.x, body.x, 1e-6);
        assertEquals(centre.y, body.y, 1e-6);
        assertEquals(centre.z, body.z, 1e-6);
        assertEquals(centre.yaw, body.yaw, 1e-3);
        assertEquals(0, body.pitch, 1e-6);
    }

    @ParameterizedTest
    @ValueSource(doubles = {17, 19, 20, 21, 23})
    void acrossAChangeOfGradeBothBogiesStayOnTheRail(double s) {
        // Level for 20 blocks, then down at 10 degrees.
        TrackSpline line = spline(new double[]{0.5, 64, 0}, new double[]{0.5, 64, 20},
                new double[]{0.5, 64 - 20 * Math.tan(Math.toRadians(10)), 40});
        TrackPose body = Bogies.bodyPose(line, s, FRONT, BACK);
        assertOnRail(line, body, s, FRONT, 0.02);
        assertOnRail(line, body, s, BACK, 0.02);
        assertTrue(body.pitch >= -1e-3 && body.pitch <= 10.01, "Body pitch " + body.pitch);
    }

    @ParameterizedTest
    @ValueSource(doubles = {10, 25, 40})
    void roundABendBothBogiesStayOnTheRail(double s) {
        List<double[]> arc = new ArrayList<>();
        for (int i = 0; i <= 30; i++) {
            double a = Math.toRadians(i * 3);
            arc.add(new double[]{0.5 + 25 * (1 - Math.cos(a)), 64, 25 * Math.sin(a)});
        }
        TrackSpline bend = TrackSpline.fromPoints(UUID.randomUUID(), "world", false, arc);
        TrackPose body = Bogies.bodyPose(bend, s, FRONT, BACK);
        assertOnRail(bend, body, s, FRONT, 0.05);
        assertOnRail(bend, body, s, BACK, 0.05);
        // The body is the chord between the bogies, so its middle sits inside the curve.
        TrackPose centre = bend.sampleAt(s);
        assertTrue(Math.hypot(body.x - centre.x, body.z - centre.z) > 0.01);
    }

    @Test
    void pastTheEndOfTheTrackTheBogieCarriesOnStraight() {
        TrackSpline line = spline(new double[]{0.5, 64, 0}, new double[]{0.5, 64, 10});
        TrackPose body = Bogies.bodyPose(line, 9.5, FRONT, BACK);
        assertEquals(9.5, body.z, 1e-6);
        assertEquals(0, body.pitch, 1e-6);
    }

    @Test
    void bogieTurnsByTheDifferenceBetweenRailAndBody() {
        TrackPose body = new TrackPose(0, 64, 0, 10, 2);
        TrackPose rail = new TrackPose(0, 64, 2, 15, 5);
        float[] turn = Bogies.turn(body, rail);
        assertEquals(-5, turn[0], 1e-5);
        assertEquals(3, turn[1], 1e-5);
        float[] across = Bogies.turn(new TrackPose(0, 64, 0, 179, 0), new TrackPose(0, 64, 0, -179, 0));
        assertEquals(-2, across[0], 1e-4);
    }

    @Test
    void bogieOnTrackLaidTheOtherWayTurnsTheSameAsOnOurs() {
        TrackPose body = new TrackPose(0, 64, 0, 10, 2);
        float[] same = Bogies.turn(body, new TrackPose(0, 64, 2, 15, 5));
        float[] reversed = Bogies.turn(body, new TrackPose(0, 64, 2, 15 - 180, -5));
        assertEquals(same[0], reversed[0], 1e-4);
        assertEquals(same[1], reversed[1], 1e-4);
    }

    @Test
    void bodyRestsOnTwoRailsGivenSeparately() {
        // As if the front bogie had crossed onto a branch: its rail comes from another track.
        TrackPose front = new TrackPose(0.5 + 0.3, 64, 22.125, -8f, 0f);
        TrackPose back = new TrackPose(0.5, 64, 17.875, 0f, 0f);
        TrackPose body = Bogies.bodyPose(front, back, FRONT, BACK);
        assertEquals(0.65, body.x, 1e-6);
        assertEquals(20, body.z, 1e-6);
        double yaw = Math.toDegrees(Math.atan2(-0.3, 4.25));
        assertEquals(yaw, body.yaw, 1e-4);
        // Order of the rails follows the offsets, not which is ahead.
        TrackPose swapped = Bogies.bodyPose(back, front, BACK, FRONT);
        assertEquals(body.x, swapped.x, 1e-9);
        assertEquals(body.yaw, swapped.yaw, 1e-6);
    }

    @Test
    void bogiesAtOnePointKeepTheOriginOffTheirRail() {
        TrackSpline line = spline(new double[]{0.5, 64, 0}, new double[]{0.5, 64, 40});
        TrackPose body = Bogies.bodyPose(line, 20, 1.5, 1.5);
        assertEquals(20, body.z, 1e-6);
        assertOnRail(line, body, 20, 1.5, 1e-6);
    }

    // The bogie centre, placed from the body pose, lies on the rail at its own distance along.
    private static void assertOnRail(TrackSpline line, TrackPose body, double s, double offset, double tolerance) {
        double yaw = Math.toRadians(body.yaw);
        double pitch = Math.toRadians(body.pitch);
        double x = body.x - Math.sin(yaw) * Math.cos(pitch) * offset;
        double y = body.y - Math.sin(pitch) * offset;
        double z = body.z + Math.cos(yaw) * Math.cos(pitch) * offset;
        TrackPose rail = Bogies.rail(line, s + offset);
        double miss = Math.sqrt((x - rail.x) * (x - rail.x) + (y - rail.y) * (y - rail.y) + (z - rail.z) * (z - rail.z));
        assertTrue(miss < tolerance, "Bogie at " + offset + " misses the rail by " + miss);
    }

    private static TrackSpline spline(double[]... points) {
        return TrackSpline.fromPoints(UUID.randomUUID(), "world", false, List.of(points));
    }
}
