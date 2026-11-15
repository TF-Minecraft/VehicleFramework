package net.tfminecraft.vehicleframework.ownership;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import net.tfminecraft.vehicleframework.api.VehicleOwners;
import net.tfminecraft.vehicleframework.events.ServerVehicleUseEvent;
import net.tfminecraft.vehicleframework.permissions.Permissions;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;

/** Server ownership is independent of player identities, whitelists, groups and tickets. */
public final class ServerVehicles {
	private ServerVehicles() {
	}

	public static boolean isServer(ActiveVehicle vehicle) {
		return vehicle != null && vehicle.getOwnerData() != null
				&& VehicleOwners.isServer(vehicle.getOwnerData().getOwner());
	}

	public static boolean mayUse(Player player, ActiveVehicle vehicle) {
		if (player.hasPermission("vf.server.use") || Permissions.isAdmin(player)) return true;
		ServerVehicleUseEvent event = new ServerVehicleUseEvent(player, vehicle);
		Bukkit.getPluginManager().callEvent(event);
		return event.isAllowed();
	}

	/** Check the car and locomotive separately; deck clicks already resolve to their car. */
	public static boolean mayAccess(Player player, ActiveVehicle vehicle) {
		if (isServer(vehicle) && !mayUse(player, vehicle)) return false;
		ActiveVehicle source = vehicle.ticketSource();
		return source == vehicle || !isServer(source) || mayUse(player, source);
	}

	/** Shared interaction refusal, also used for stale menus and pending entity mounts. */
	public static boolean check(Player player, ActiveVehicle vehicle) {
		if (mayAccess(player, vehicle)) return true;
		player.sendMessage("§cThis is a server vehicle.");
		return false;
	}
}
