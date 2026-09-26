package net.tfminecraft.vehicleframework.vehicles.component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import java.util.List;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Test;

import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.controller.VehicleMovementController;
import net.tfminecraft.vehicleframework.vehicles.handlers.TrainHandler;
import net.tfminecraft.vehicleframework.vehicles.handlers.BehaviourHandler;
import net.tfminecraft.vehicleframework.vehicles.handlers.train.LocomotiveOverdrive;
import net.tfminecraft.vehicleframework.vehicles.util.AccessPanel;

class LocomotiveEngineTest {
    @Test
    void enginePublishesBoostedSpeedAndChargesBoostedFuel() {
        Fixture f = fixture();
        f.engine.getThrottle().setThrottle(120);
        f.engine.tick(List.of());
        assertEquals(120, f.engine.getThrottle().getCurrent());
        verify(f.panel).setSpeed(0.864);
        f.engine.slowTick(List.of());
        assertEquals(97.5, f.engine.getFuelTank().getCurrent());
    }

    @Test
    void cooldownAllowsNormalSpeedAndFuelButClampsBoostRequests() {
        Fixture f = fixture();
        long now = System.currentTimeMillis();
        f.overdrive.update(120, now - 10_000);
        f.overdrive.update(120, now);
        f.engine.getThrottle().setThrottle(120);
        f.engine.tick(List.of());
        assertEquals(100, f.engine.getThrottle().getCurrent());
        verify(f.panel).setSpeed(0.72);
        f.engine.slowTick(List.of());
        assertEquals(98.75, f.engine.getFuelTank().getCurrent());
    }

    @Test
    void engineDamageStillLimitsNormalAndBoostedThrottle() {
        Fixture f = fixture();
        f.engine.getHealthData().setDamage(20);
        f.engine.getThrottle().setThrottle(120);
        f.engine.tick(List.of());
        assertEquals(80, f.engine.getThrottle().getCurrent());
        assertEquals(0.576, f.engine.getSpeed(), 0.00001);
        assertEquals(0, f.overdrive.remainingSeconds());
    }

    @Test
    void otherTrainEnginesKeepTheirExistingThrottleLimitAndFuelRate() {
        Fixture f = fixture();
        when(f.vehicle.isLocomotive()).thenReturn(false);
        f.engine.getThrottle().setThrottle(120);
        f.engine.tick(List.of());
        assertEquals(100, f.engine.getThrottle().getCurrent());
        assertEquals(0, f.overdrive.remainingSeconds());
        f.engine.slowTick(List.of());
        assertEquals(98.75, f.engine.getFuelTank().getCurrent());
    }

    private Fixture fixture() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("health", 100);
        config.set("max", 120);
        config.set("min", -100);
        config.set("speed", 0.72);
        config.set("fuel-burn-rate", 1.25);
        ActiveVehicle vehicle = mock(ActiveVehicle.class);
        AccessPanel panel = mock(AccessPanel.class);
        TrainHandler train = mock(TrainHandler.class);
        LocomotiveOverdrive overdrive = new LocomotiveOverdrive();
        when(vehicle.isTrain()).thenReturn(true);
        when(vehicle.isLocomotive()).thenReturn(true);
        when(vehicle.getTrainHandler()).thenReturn(train);
        when(vehicle.getBehaviourHandler()).thenReturn(mock(BehaviourHandler.class));
        when(vehicle.getAccessPanel()).thenReturn(panel);
        when(vehicle.getMoveControls()).thenReturn(mock(VehicleMovementController.class));
        // Recording keeps the engine running without scheduling unattended-engine shutdown.
        when(train.isRecording()).thenReturn(true);
        when(train.playbackThrottle(any())).thenReturn(null);
        when(train.getOverdrive()).thenReturn(overdrive);
        Engine engine = new Engine(vehicle, new Engine(config), mock(Entity.class), null, null);
        engine.getFuelTank().setFuel(100);
        return new Fixture(vehicle, engine, panel, overdrive);
    }

    private record Fixture(ActiveVehicle vehicle, Engine engine, AccessPanel panel,
                           LocomotiveOverdrive overdrive) {}
}
