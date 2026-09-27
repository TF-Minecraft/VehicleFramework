package net.tfminecraft.vehicleframework.vehicles.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import net.tfminecraft.vehicleframework.enums.Animation;
import net.tfminecraft.vehicleframework.enums.Direction;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.controller.VehicleMovementController;
import net.tfminecraft.vehicleframework.vehicles.handlers.state.AnimationHandler;

class TrainWheelSpinTest {
    @Test
    void wheelsTurnOncePerCircumference() {
        // 1 block across, moving pi / 20 blocks a tick: one turn a second.
        assertEquals(1, TrainHandler.wheelTurnsPerSecond(Math.PI / 20, 1), 1e-9);
        assertEquals(2, TrainHandler.wheelTurnsPerSecond(-Math.PI / 10, 1), 1e-9);
    }

    @Test
    void forwardMoveSetsWheelSpeedOnEveryCar() {
        TrainHandler loco = car(1.875);
        TrainHandler first = car(1.0);
        loco.setChild(first.v);
        loco.animateMove(Direction.FORWARD, 0.5);
        verify(loco.v.getAnimationHandler()).animateWheels(Direction.FORWARD, 0.5 * 20 / (Math.PI * 1.875));
        verify(first.v.getAnimationHandler()).animateWheels(Direction.FORWARD, 0.5 * 20 / Math.PI);
    }

    @Test
    void backwardMoveSetsBackwardAnimationSpeed() {
        TrainHandler loco = car(1.0);
        loco.animateMove(Direction.BACKWARD, -0.25);
        verify(loco.v.getAnimationHandler()).animateWheels(Direction.BACKWARD, 0.25 * 20 / Math.PI);
    }

    @Test
    void carsWithoutWheelSizeKeepAuthoredSpeed() {
        TrainHandler loco = car(0);
        loco.animateMove(Direction.FORWARD, 0.5);
        verify(loco.v.getMoveControls()).animateMove(Direction.FORWARD);
        verify(loco.v, never()).setAnimationSpeed(eq(Animation.FORWARD), anyDouble());
    }

    @Test
    void stoppingPausesWheelsWithoutStoppingTheAnimation() {
        TrainHandler loco = car(1.0);
        loco.animateMove(Direction.STILL);
        verify(loco.v.getAnimationHandler()).animateWheels(Direction.STILL, 0);
        verify(loco.v.getMoveControls(), never()).animateMove(Direction.STILL);
        verify(loco.v, never()).setAnimationSpeed(eq(Animation.FORWARD), anyDouble());
        verify(loco.v, never()).setAnimationSpeed(eq(Animation.BACKWARD), anyDouble());
    }

    private static TrainHandler car(double wheelDiameter) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("wheel-diameter", wheelDiameter);
        TrainHandler handler = new TrainHandler(config);
        ActiveVehicle vehicle = mock(ActiveVehicle.class);
        when(vehicle.getMoveControls()).thenReturn(mock(VehicleMovementController.class));
        when(vehicle.getAnimationHandler()).thenReturn(mock(AnimationHandler.class));
        when(vehicle.getTrainHandler()).thenReturn(handler);
        handler.v = vehicle;
        return handler;
    }
}
