package net.tfminecraft.vehicleframework.vehicles.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TerrainRestTest {
    private final TerrainRest rest = new TerrainRest();

    /** One full step that leaves the vehicle where it stood, level. */
    private void stillStep(double x, double y, double z) {
        rest.observe(x, y, z, x, y, z, false, true, 0f, 0f);
    }

    private void settleAt(double x, double y, double z) {
        for (int i = 0; i < TerrainRest.SETTLE_STEPS; i++) {
            assertFalse(rest.skip(x, y, z), "step " + i + " must still run");
            stillStep(x, y, z);
        }
        assertTrue(rest.settled());
    }

    @Test
    void everyStepRunsUntilEnoughOfThemInARowChangeNothing() {
        for (int i = 0; i < TerrainRest.SETTLE_STEPS - 1; i++) {
            stillStep(8, 64, 8);
            assertFalse(rest.settled());
            assertFalse(rest.skip(8, 64, 8));
        }
        stillStep(8, 64, 8);
        assertTrue(rest.settled());
        assertTrue(rest.skip(8, 64, 8));
    }

    @Test
    void aSettledVehicleRunsOneFullStepInEveryRecheckWindow() {
        settleAt(8, 64, 8);
        for (int window = 0; window < 3; window++) {
            int skipped = 0;
            while (rest.skip(8, 64, 8)) {
                skipped++;
            }
            assertEquals(TerrainRest.RECHECK_TICKS - 1, skipped);
            stillStep(8, 64, 8);
            assertTrue(rest.settled(), "an unchanged recheck keeps the vehicle settled");
        }
    }

    @Test
    void aVehicleFoundAnywhereElseRunsEveryStepAgain() {
        settleAt(8, 64, 8);
        assertTrue(rest.skip(8, 64, 8));
        assertFalse(rest.skip(8, 63.9, 8), "it fell");
        assertFalse(rest.settled());
        assertFalse(rest.skip(8, 64, 8), "back on the old spot is not settled either");
    }

    @Test
    void aStepThatMovesOrPushesTheVehicleStartsTheCountAgain() {
        settleAt(8, 64, 8);
        rest.observe(8, 64, 8, 8, 63.5, 8, false, true, 0f, 0f);
        assertFalse(rest.settled(), "the step dropped it half a block");
        settleAt(8, 63.5, 8);
        rest.observe(8, 63.5, 8, 8, 63.5, 8, true, true, 0f, 0f);
        assertFalse(rest.settled(), "the step gave it velocity");
    }

    @Test
    void aChangingTiltOrALostTiltKeepsTheStepRunningUntilItHolds() {
        for (int i = 0; i < TerrainRest.SETTLE_STEPS * 2; i++) {
            rest.observe(8, 64, 8, 8, 64, 8, false, true, i * .5f, 0f);
            assertFalse(rest.settled(), "the tilt is still turning");
        }
        settleAt(8, 64, 8);
        rest.observe(8, 64, 8, 8, 64, 8, false, false, 0f, 0f);
        assertFalse(rest.settled(), "a wheel left the ground, so the step gave no tilt");
        for (int i = 0; i < TerrainRest.SETTLE_STEPS - 1; i++) {
            rest.observe(8, 64, 8, 8, 64, 8, false, false, 0f, 0f);
        }
        assertTrue(rest.settled(), "no tilt at all also settles once it holds");
    }

    @Test
    void aStillStepSomewhereNewCountsAsTheFirstOfItsRun() {
        settleAt(8, 64, 8);
        stillStep(20, 70, 20);
        assertFalse(rest.settled());
        for (int i = 0; i < TerrainRest.SETTLE_STEPS - 2; i++) {
            stillStep(20, 70, 20);
            assertFalse(rest.settled());
        }
        stillStep(20, 70, 20);
        assertTrue(rest.settled());
    }

    @Test
    void everyAxisAndBothTiltAnglesCount() {
        double[][] elsewhere = {{8.5, 64, 8}, {8, 64.5, 8}, {8, 64, 8.5}};
        for (double[] spot : elsewhere) {
            settleAt(8, 64, 8);
            assertFalse(rest.skip(spot[0], spot[1], spot[2]), "found off its spot on one axis");
            settleAt(8, 64, 8);
            rest.observe(8, 64, 8, spot[0], spot[1], spot[2], false, true, 0f, 0f);
            assertFalse(rest.settled(), "the step moved it along one axis");
            rest.reset();
        }
        settleAt(8, 64, 8);
        rest.observe(8, 64, 8, 8, 64, 8, false, true, 0f, 2f);
        assertFalse(rest.settled(), "the roll changed");
        settleAt(8, 64, 8);
        rest.observe(8, 64, 8, 8, 64, 8, false, true, 2f, 0f);
        assertFalse(rest.settled(), "the pitch changed");
    }

    @Test
    void resetForgetsTheSpot() {
        settleAt(8, 64, 8);
        rest.reset();
        assertFalse(rest.settled());
        assertFalse(rest.skip(8, 64, 8));
    }
}
