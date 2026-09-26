package net.tfminecraft.vehicleframework.vehicles.component.fuel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;

import org.junit.jupiter.api.Test;

import net.tfminecraft.vehicleframework.vehicles.component.propulsion.Throttle;

class FuelTankTest {
    @Test
    void locomotiveFuelBurnUsesReducedBaseAndOverdriveCurve() {
        for (int power : new int[] {100, 110, 120}) {
            FuelTank tank = new FuelTank(100, 100, 1.25, new ArrayList<>(), null);
            Throttle throttle = new Throttle("Throttle", 120, -100, null);
            throttle.setThrottle(power);
            tank.tick(throttle, net.tfminecraft.vehicleframework.vehicles.handlers.train.LocomotiveOverdrive.fuelMultiplier(power));
            double expected = power == 100 ? 1.25 : power == 110 ? 1.5625 : 2.5;
            assertEquals(100 - expected, tank.getCurrent(), 0.00001);
        }
    }

    @Test
    void tick_reverseGear_doesNotInstantlyDrainFuel() {
        FuelTank tank = new FuelTank(500, 500, 1, new ArrayList<>(), null);
        Throttle reverse = new Throttle("Reverse", 0, -20, null);
        reverse.setThrottle(-10);

        tank.tick(reverse);

        assertTrue(tank.getCurrent() > 0, "reverse throttle must not drain entire tank in one tick");
        assertEquals(499, tank.getCurrent(), 0.001);
    }

    @Test
    void tick_forwardGear_burnsAtConfiguredRate() {
        FuelTank tank = new FuelTank(500, 500, 1, new ArrayList<>(), null);
        Throttle forward = new Throttle("First", 40, 0, null);
        forward.setThrottle(20);

        tank.tick(forward);

        assertEquals(499, tank.getCurrent(), 0.001);
    }

    @Test
    void tick_idleThrottle_doesNotBurnFuel() {
        FuelTank tank = new FuelTank(500, 500, 1, new ArrayList<>(), null);
        Throttle reverse = new Throttle("Reverse", 0, -20, null);

        tank.tick(reverse);

        assertEquals(500, tank.getCurrent(), 0.001);
    }
}
