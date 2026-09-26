package net.tfminecraft.vehicleframework.weapons;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Rate-limits a repeated chat notice per player.
 */
public final class NoticeCooldown {
	private final long cooldownMillis;
	private final Map<UUID, Long> lastSent = new HashMap<>();

	public NoticeCooldown(long cooldownMillis) {
		this.cooldownMillis = cooldownMillis;
	}

	/**
	 * Returns true and starts the cooldown if the player may be sent the notice at {@code now}.
	 */
	public boolean tryAcquire(UUID player, long now) {
		Long last = lastSent.get(player);
		if (last != null && now - last < cooldownMillis) {
			return false;
		}
		lastSent.put(player, now);
		return true;
	}
}
