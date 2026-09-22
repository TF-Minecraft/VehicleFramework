package net.tfminecraft.VehicleFramework.Util;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

class DeepWaterCentreTest {
    @Test
    void twoWaterBlocksOverFloorPreventGroundEvenWhenFeetTouchFloor() {
        Map<Integer, Material> column = Map.of(0, Material.STONE, 1, Material.WATER, 2, Material.WATER);
        assertTrue(LocationChecker.hasDeepWaterAtCentre(block(column, 0)));
        assertTrue(LocationChecker.hasDeepWaterAtCentre(block(column, 1)));
    }

    @Test
    void singleWaterBlockOverFloorRemainsWadable() {
        Map<Integer, Material> column = Map.of(0, Material.STONE, 1, Material.WATER);
        assertFalse(LocationChecker.hasDeepWaterAtCentre(block(column, 0)));
        assertFalse(LocationChecker.hasDeepWaterAtCentre(block(column, 1)));
    }

    @Test
    void kelpAndSeagrassCountAsWater() {
        assertTrue(LocationChecker.hasDeepWaterAtCentre(block(
                Map.of(0, Material.STONE, 1, Material.KELP_PLANT, 2, Material.SEAGRASS), 0)));
    }

    @Test
    void dryCentreDoesNotFloatFromUnrelatedWaterAbove() {
        assertFalse(LocationChecker.hasDeepWaterAtCentre(block(
                Map.of(1, Material.WATER, 2, Material.WATER), 0)));
    }

    private Block block(Map<Integer, Material> column, int y) {
        Material type = column.getOrDefault(y, Material.AIR);
        return (Block) Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[]{Block.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getType" -> type;
                    case "isLiquid" -> type == Material.WATER;
                    case "isPassable" -> type != Material.STONE;
                    case "getBlockData" -> null;
                    case "getRelative" -> {
                        BlockFace face = (BlockFace) args[0];
                        assertEquals(0, face.getModX(), "Never sample neighbouring columns");
                        assertEquals(0, face.getModZ(), "Never sample neighbouring columns");
                        yield block(column, y + face.getModY());
                    }
                    default -> throw new AssertionError(method);
                });
    }
}
