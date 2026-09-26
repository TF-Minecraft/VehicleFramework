package net.tfminecraft.vehicleframework.loaders;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TrainsLoaderTest {
    @Test
    void clearanceHeightKeepsConfiguredValue() {
        assertEquals(2.5, TrainsLoader.clearanceHeight(2.5, 0.51), 1e-9);
    }

    @Test
    void clearanceHeightStaysAboveVehicleOffset() {
        assertEquals(1.01, TrainsLoader.clearanceHeight(0.3, 0.51), 1e-9);
    }
}
