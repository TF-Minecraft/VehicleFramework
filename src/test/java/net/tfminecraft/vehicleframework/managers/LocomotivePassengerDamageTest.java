package net.tfminecraft.vehicleframework.managers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;

class LocomotivePassengerDamageTest {
    @Test
    void locomotiveRidersTakeOneQuarterDamageWithExistingCap() {
        ActiveVehicle locomotive = mock(ActiveVehicle.class);
        when(locomotive.isLocomotive()).thenReturn(true);
        assertEquals(5, VehicleManager.passengerDamage(locomotive, 20));
        assertEquals(18, VehicleManager.passengerDamage(locomotive, 100));
    }

    @Test
    void ridersInMultipleAttachedCarsShareLocomotiveProtection() {
        ActiveVehicle locomotive = mock(ActiveVehicle.class);
        ActiveVehicle middle = mock(ActiveVehicle.class);
        ActiveVehicle rear = mock(ActiveVehicle.class);
        when(locomotive.isLocomotive()).thenReturn(true);
        when(rear.isTrain()).thenReturn(true);
        when(rear.hasParent()).thenReturn(true);
        when(rear.getParent()).thenReturn(middle);
        when(middle.hasParent()).thenReturn(true);
        when(middle.getParent()).thenReturn(locomotive);
        assertEquals(5, VehicleManager.passengerDamage(rear, 20));
    }

    @Test
    void otherVehiclesAndDetachedCarsKeepExistingProtection() {
        ActiveVehicle vehicle = mock(ActiveVehicle.class);
        assertEquals(10, VehicleManager.passengerDamage(vehicle, 20));
        when(vehicle.isTrain()).thenReturn(true);
        assertEquals(10, VehicleManager.passengerDamage(vehicle, 20));
        assertEquals(18, VehicleManager.passengerDamage(vehicle, 100));
    }
}
