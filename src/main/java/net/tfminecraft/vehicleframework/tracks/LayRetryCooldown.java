package net.tfminecraft.vehicleframework.tracks;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * After a refused lay, blocks further attempts for a short while. The refusal already
 * outlines what is in the way, and each attempt re-checks the whole stroke.
 */
final class LayRetryCooldown {
	private final Map<UUID, Long> until = new ConcurrentHashMap<>();

	void start(UUID player, long nowMs, long cooldownMs) {
		if (cooldownMs > 0) {
			until.put(player, nowMs + cooldownMs);
		}
	}

	/** Milliseconds left, or 0 when the player may try again. */
	long remainingMs(UUID player, long nowMs) {
		Long end = until.get(player);
		if (end == null) {
			return 0;
		}
		if (nowMs >= end) {
			until.remove(player, end);
			return 0;
		}
		return end - nowMs;
	}
}
