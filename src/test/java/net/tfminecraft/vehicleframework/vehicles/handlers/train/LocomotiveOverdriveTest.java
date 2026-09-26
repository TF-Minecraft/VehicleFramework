package net.tfminecraft.vehicleframework.vehicles.handlers.train;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LocomotiveOverdriveTest {
    @Test
    void fullBoostLastsTenSecondsThenAllowsNormalPowerThroughoutCooldown() {
        var boost = new LocomotiveOverdrive();
        assertEquals(120, boost.update(120, 1_000));
        assertEquals(10, boost.remainingSeconds());
        assertEquals(120, boost.update(120, 10_999));
        assertEquals(100, boost.update(120, 11_000));
        assertEquals(300, boost.cooldownSeconds(11_000));
        assertEquals(100, boost.update(100, 12_000));
        assertEquals(50, boost.update(50, 13_000));
        assertEquals(-100, boost.update(-100, 14_000));
        assertEquals(100, boost.update(120, 310_999));
        assertEquals(120, boost.update(120, 311_000));
    }

    @Test
    void tenPercentBoostLastsTwentySeconds() {
        var boost = new LocomotiveOverdrive();
        assertEquals(110, boost.update(110, 1_000));
        assertEquals(20, boost.remainingSeconds());
        assertEquals(110, boost.update(110, 20_999));
        assertEquals(100, boost.update(110, 21_000));
    }

    @Test
    void changingBoostSharesBudgetAndEarlyExitStartsCooldown() {
        var boost = new LocomotiveOverdrive();
        boost.update(110, 1_000);
        assertEquals(120, boost.update(120, 11_000));
        assertEquals(5, boost.remainingSeconds());
        assertEquals(100, boost.update(100, 12_000));
        assertEquals(100, boost.update(120, 12_001));
        assertEquals(300, boost.cooldownSeconds(12_001));
    }

    @Test
    void normalDrivingNeverStartsCooldownAndBoostIsCapped() {
        var boost = new LocomotiveOverdrive();
        boost.update(-100, 1_000);
        boost.update(100, 2_000);
        assertEquals(0, boost.cooldownSeconds(2_000));
        assertEquals(120, boost.update(150, 3_000));
    }

    @Test
    void savedActiveBoostAndCooldownCannotBeResetByReloading() {
        var boost = new LocomotiveOverdrive();
        boost.update(120, 1_000);
        boost.update(120, 6_000);
        var restored = new LocomotiveOverdrive();
        restored.restore(boost.toJson());
        assertEquals(120, restored.update(120, 10_999));
        assertEquals(100, restored.update(120, 11_000));
        var cooling = new LocomotiveOverdrive();
        cooling.restore(restored.toJson());
        assertEquals(100, cooling.update(120, 310_999));
        assertEquals(120, cooling.update(120, 311_000));
    }

    @Test
    void unloadedTimeCountsAndCooldownStartsAtActualExhaustion() {
        var boost = new LocomotiveOverdrive();
        boost.update(120, 1_000);
        assertEquals(100, boost.update(120, 111_000));
        assertEquals(200, boost.cooldownSeconds(111_000));
    }

    @Test
    void fuelCurveChargesTwentyFivePercentAtTenAndOneHundredPercentAtTwenty() {
        assertEquals(1, LocomotiveOverdrive.fuelMultiplier(-100));
        assertEquals(1, LocomotiveOverdrive.fuelMultiplier(100));
        assertEquals(1.25, LocomotiveOverdrive.fuelMultiplier(110));
        assertEquals(1.5625, LocomotiveOverdrive.fuelMultiplier(115));
        assertEquals(2, LocomotiveOverdrive.fuelMultiplier(120));
    }
}
