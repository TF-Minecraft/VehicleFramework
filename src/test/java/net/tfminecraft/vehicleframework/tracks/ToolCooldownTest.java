package net.tfminecraft.vehicleframework.tracks;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class ToolCooldownTest {
    private final UUID player = UUID.randomUUID();

    @Test
    void noRefusalMeansNoWait() {
        assertEquals(0, new ToolCooldown().remainingMs(player, 1000));
    }

    @Test
    void refusalBlocksUntilTheCooldownEnds() {
        ToolCooldown retry = new ToolCooldown();
        retry.start(player, 1000, 3000);
        assertEquals(3000, retry.remainingMs(player, 1000));
        assertEquals(1, retry.remainingMs(player, 3999));
        assertEquals(0, retry.remainingMs(player, 4000));
        assertEquals(0, retry.remainingMs(player, 4001));
    }

    @Test
    void zeroCooldownNeverBlocks() {
        ToolCooldown retry = new ToolCooldown();
        retry.start(player, 1000, 0);
        assertEquals(0, retry.remainingMs(player, 1000));
    }

    @Test
    void cooldownIsPerPlayer() {
        ToolCooldown retry = new ToolCooldown();
        retry.start(player, 1000, 3000);
        assertEquals(0, retry.remainingMs(UUID.randomUUID(), 1000));
    }
}
