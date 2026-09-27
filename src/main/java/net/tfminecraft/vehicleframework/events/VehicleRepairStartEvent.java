package net.tfminecraft.vehicleframework.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;

/**
 * Fired when a player starts a vehicle repair: when the repair item opens the repair
 * menu (before seat/speed checks), and again when a component or weapon repair or a
 * fire fight starts from that menu. Cancel to block the repair.
 */
public final class VehicleRepairStartEvent extends PlayerEvent implements Cancellable {
	private static final HandlerList HANDLERS = new HandlerList();

	private final ActiveVehicle vehicle;
	private boolean cancelled;

	public VehicleRepairStartEvent(Player player, ActiveVehicle vehicle) {
		super(player);
		this.vehicle = vehicle;
	}

	public ActiveVehicle getVehicle() {
		return vehicle;
	}

	@Override
	public boolean isCancelled() {
		return cancelled;
	}

	@Override
	public void setCancelled(boolean cancelled) {
		this.cancelled = cancelled;
	}

	@Override
	public HandlerList getHandlers() {
		return HANDLERS;
	}

	public static HandlerList getHandlerList() {
		return HANDLERS;
	}
}
