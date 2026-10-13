package net.tfminecraft.vehicleframework.ownership;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.vehicleframework.VehicleFramework;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;

/**
 * Chat and click actions waiting for the player's next input. Each records the session it started
 * under (characters mode), expires after 60 seconds and ends with the session.
 */
public final class PendingActions {
	public static final long TIMEOUT_TICKS = 1200L;

	public enum Kind {
		WHITELIST_ADD("§cWhitelist entry timed out."),
		RENAME("§cNaming timed out."),
		TAKEOVER("§cTakeover expired.");

		private final String timeoutMessage;

		Kind(String timeoutMessage) {
			this.timeoutMessage = timeoutMessage;
		}
	}

	/**
	 * @param session the session it started under, or {@code null} outside characters mode
	 * @param target  the takeover's owner value and display name, or {@code null}
	 */
	public record Pending(Kind kind, ActiveVehicle vehicle, CharacterSession session, Target target, int taskId) {
	}

	/** The owner a takeover writes, with the character and the name to report. */
	public record Target(String ownerValue, CharacterRef character, String name) {
	}

	private record Key(UUID player, Kind kind) {
	}

	private final Map<Key, Pending> pending = new ConcurrentHashMap<>();

	public void start(Player player, Kind kind, ActiveVehicle vehicle, CharacterSession session, Target target) {
		cancel(player, kind);
		Key key = new Key(player.getUniqueId(), kind);
		Pending[] started = new Pending[1];
		int taskId = Bukkit.getScheduler().runTaskLater(VehicleFramework.plugin, () -> {
			if (pending.remove(key, started[0])) {
				player.sendMessage(kind.timeoutMessage);
			}
		}, TIMEOUT_TICKS).getTaskId();
		started[0] = new Pending(kind, vehicle, session, target, taskId);
		pending.put(key, started[0]);
	}

	public boolean has(Player player, Kind kind) {
		return pending.containsKey(new Key(player.getUniqueId(), kind));
	}

	/** Removes and returns the action, cancelling its timeout. */
	public Optional<Pending> take(Player player, Kind kind) {
		Pending removed = pending.remove(new Key(player.getUniqueId(), kind));
		if (removed != null) {
			Bukkit.getScheduler().cancelTask(removed.taskId());
		}
		return Optional.ofNullable(removed);
	}

	public void cancel(Player player, Kind kind) {
		take(player, kind);
	}

	/** Drops every action of the player; returns whether there were any. */
	public boolean clear(Player player) {
		boolean any = false;
		for (Kind kind : Kind.values()) {
			any |= take(player, kind).isPresent();
		}
		return any;
	}

	/** Whether the session the action started under is still being played. */
	public static boolean stillCurrent(Pending action) {
		return action.session() == null || CharacterLookup.isCurrent(action.session());
	}
}
