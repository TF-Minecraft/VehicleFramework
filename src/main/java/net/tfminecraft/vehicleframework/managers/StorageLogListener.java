package net.tfminecraft.vehicleframework.managers;

import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;

import net.tfminecraft.vehicleframework.VehicleFramework;
import net.tfminecraft.vehicleframework.database.StorageLog;
import net.tfminecraft.vehicleframework.enums.VFGUI;
import net.tfminecraft.vehicleframework.managers.inventory.VFInventoryHolder;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.handlers.container.Container;
import net.tfminecraft.vehicleframework.vehicles.handlers.container.ItemCounts;
import net.tfminecraft.vehicleframework.vehicles.handlers.container.StorageAudit;

/**
 * Logs every item put into or taken out of a vehicle's storage, by whom. Storage is a shared
 * inventory any number of players may have open. Each click or drag is counted once the server
 * has applied it, so shift-clicks, drags, number keys, double-click gathering and dropping from a
 * slot all show as the items that actually moved. Counting before other plugins see the next
 * click keeps what they do with a click on the clicker. Other inventories cost one holder check.
 */
public class StorageLogListener implements Listener {

	/** The vehicle storage a view shows on top, if it shows one. */
	record Storage(ActiveVehicle vehicle, Container container, Inventory inventory) {
		StorageAudit audit() {
			return container.getAudit();
		}
	}

	static Storage storage(InventoryView view) {
		if (!StorageLog.isEnabled()) {
			return null;
		}
		Inventory top = view.getTopInventory();
		// Without a snapshot: a block's holder would otherwise be copied on every click.
		if (!(top.getHolder(false) instanceof VFInventoryHolder holder) || holder.getType() != VFGUI.CONTAINER) {
			return null;
		}
		ActiveVehicle vehicle = holder.getVehicle().orElse(null);
		if (vehicle == null || !vehicle.hasContainers()) {
			return null;
		}
		Container container = vehicle.getContainerHandler().get(holder.getId());
		return container == null ? null : new Storage(vehicle, container, top);
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void open(InventoryOpenEvent e) {
		Storage storage = storage(e.getView());
		if (storage == null || !(e.getPlayer() instanceof Player player)) {
			return;
		}
		count(storage);
		storage.audit().watch(storage.inventory());
		StorageLog.opened(player, storage.vehicle(), storage.container());
	}

	// Before any other plugin handles the click, the previous action is applied: count it.
	@EventHandler(priority = EventPriority.LOWEST)
	public void beforeClick(InventoryClickEvent e) {
		countBefore(e.getView());
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void beforeDrag(InventoryDragEvent e) {
		countBefore(e.getView());
	}

	// Whatever changes from here on, until the next count, is this player's doing: the click
	// itself once the server applies it, or what another plugin did with it, even if cancelled.
	@EventHandler(priority = EventPriority.MONITOR)
	public void click(InventoryClickEvent e) {
		acted(e.getView(), e.getWhoClicked());
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void drag(InventoryDragEvent e) {
		acted(e.getView(), e.getWhoClicked());
	}

	private void countBefore(InventoryView view) {
		Storage storage = storage(view);
		if (storage != null) {
			count(storage);
		}
	}

	// Counted at the next action, at the end of the tick, or when the storage is closed.
	private void acted(InventoryView view, HumanEntity who) {
		Storage storage = storage(view);
		if (storage == null) {
			return;
		}
		StorageAudit audit = storage.audit();
		if (!(who instanceof Player player) || !audit.isWatching()) {
			return;
		}
		audit.acted(player);
		if (audit.queueCount()) {
			Bukkit.getScheduler().runTask(VehicleFramework.plugin, () -> {
				audit.countRan();
				count(storage);
			});
		}
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void closing(InventoryCloseEvent e) {
		Storage storage = storage(e.getView());
		if (storage != null) {
			count(storage);
		}
	}

	// After VehicleManager#saveContainer hands disallowed items back to the player closing it.
	@EventHandler(priority = EventPriority.MONITOR)
	public void closed(InventoryCloseEvent e) {
		Storage storage = storage(e.getView());
		if (storage == null) {
			return;
		}
		StorageAudit audit = storage.audit();
		if (e.getPlayer() instanceof Player player && audit.isWatching()) {
			audit.acted(player);
			count(storage);
		}
		HumanEntity closing = e.getPlayer();
		if (storage.inventory().getViewers().stream().allMatch(closing::equals)) {
			audit.stop();
		}
	}

	// Logs what changed since the last count as the waiting player's doing. A change nobody
	// is waiting on is not a player's, and only moves the count on.
	static void count(Storage storage) {
		StorageAudit audit = storage.audit();
		Player player = audit.takePending();
		if (!audit.isWatching()) {
			return;
		}
		List<ItemCounts.Change> changes = audit.count(storage.inventory());
		if (player == null) {
			return;
		}
		for (ItemCounts.Change change : changes) {
			StorageLog.changed(player, storage.vehicle(), storage.container(), change.item(), change.amount());
		}
	}
}
