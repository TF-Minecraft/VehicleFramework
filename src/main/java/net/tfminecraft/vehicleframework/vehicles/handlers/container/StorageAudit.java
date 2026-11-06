package net.tfminecraft.vehicleframework.vehicles.handlers.container;

import java.util.List;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Who changed one vehicle storage, while anyone has it open. Each player action is counted once
 * the server has applied it: when the next action on the storage starts, at the end of the tick,
 * or when a viewer closes it. What changed since the previous count is that player's doing, so
 * actions by several viewers in one tick are told apart. Items the plugin itself takes out (a
 * locomotive drawing fuel from its tender) are subtracted as they go, so no player is blamed.
 */
public final class StorageAudit {
	// What the storage held at the last count; null while nobody has it open.
	private ItemCounts counted;
	// The player whose action has not been counted yet.
	private Player pending;
	private boolean queued;

	public boolean isWatching() {
		return counted != null;
	}

	/** Starts counting from what the storage holds now, unless someone already has it open. */
	public void watch(Inventory inventory) {
		if (counted == null) {
			counted = ItemCounts.of(inventory.getContents());
		}
	}

	/** The last viewer closed it. */
	public void stop() {
		counted = null;
		pending = null;
	}

	public void acted(Player player) {
		pending = player;
	}

	/** The player whose action is waiting to be counted, now taken off the list. */
	public Player takePending() {
		Player player = pending;
		pending = null;
		return player;
	}

	/** Whether a count at the end of this tick is still to be asked for. */
	public boolean queueCount() {
		if (queued) {
			return false;
		}
		queued = true;
		return true;
	}

	public void countRan() {
		queued = false;
	}

	/** What changed since the last count, which this becomes. */
	public List<ItemCounts.Change> count(Inventory inventory) {
		ItemCounts now = ItemCounts.of(inventory.getContents());
		List<ItemCounts.Change> changes = now.since(counted);
		counted = now;
		return changes;
	}

	/** The plugin took these out itself; no player did. */
	public void takenByPlugin(ItemStack item, int amount) {
		if (counted != null) {
			counted.add(item, -amount);
		}
	}

	/** The plugin replaced the contents itself; count again from them. */
	public void recount(Inventory inventory) {
		if (counted != null && inventory != null) {
			counted = ItemCounts.of(inventory.getContents());
		}
	}
}
