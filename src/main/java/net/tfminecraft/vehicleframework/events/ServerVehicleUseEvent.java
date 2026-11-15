package net.tfminecraft.vehicleframework.events;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;

/** Synchronous request to admit a player without server-use permission. Denied by default. */
public final class ServerVehicleUseEvent extends PlayerEvent {
	private static final HandlerList HANDLERS = new HandlerList();
	private final ActiveVehicle vehicle;
	private boolean allowed;

	public ServerVehicleUseEvent(Player player, ActiveVehicle vehicle) {
		super(player);
		this.vehicle = vehicle;
	}

	public ActiveVehicle getVehicle() {
		return vehicle;
	}

	public boolean isAllowed() {
		return allowed;
	}

	public void setAllowed(boolean allowed) {
		this.allowed = allowed;
	}

	@Override
	public HandlerList getHandlers() {
		return HANDLERS;
	}

	public static HandlerList getHandlerList() {
		return HANDLERS;
	}
}
