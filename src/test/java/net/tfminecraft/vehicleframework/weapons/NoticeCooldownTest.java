package net.tfminecraft.vehicleframework.weapons;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class NoticeCooldownTest {

	@Test
	void tryAcquire_firstNotice_isAllowed() {
		NoticeCooldown cooldown = new NoticeCooldown(10_000);
		assertTrue(cooldown.tryAcquire(UUID.randomUUID(), 1_000));
	}

	@Test
	void tryAcquire_withinCooldown_isBlocked() {
		NoticeCooldown cooldown = new NoticeCooldown(10_000);
		UUID player = UUID.randomUUID();
		assertTrue(cooldown.tryAcquire(player, 1_000));
		assertFalse(cooldown.tryAcquire(player, 10_999));
	}

	@Test
	void tryAcquire_afterCooldown_isAllowedAgain() {
		NoticeCooldown cooldown = new NoticeCooldown(10_000);
		UUID player = UUID.randomUUID();
		assertTrue(cooldown.tryAcquire(player, 1_000));
		assertTrue(cooldown.tryAcquire(player, 11_000));
	}

	@Test
	void tryAcquire_tracksPlayersSeparately() {
		NoticeCooldown cooldown = new NoticeCooldown(10_000);
		assertTrue(cooldown.tryAcquire(UUID.randomUUID(), 1_000));
		assertTrue(cooldown.tryAcquire(UUID.randomUUID(), 1_000));
	}
}
