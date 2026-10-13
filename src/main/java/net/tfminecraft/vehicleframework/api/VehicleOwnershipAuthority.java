package net.tfminecraft.vehicleframework.api;

/**
 * Registered in Bukkit's {@code ServicesManager} by the plugin that vetoes ownership changes
 * (SimpleFactions), once it handles {@link net.tfminecraft.vehicleframework.events.VehicleOwnershipChangeEvent}.
 *
 * <p>In characters mode, while SimpleFactions is installed (enabled or not) and no ready authority
 * is registered, VehicleFramework refuses every ownership change by a player without
 * {@code vf.admin}. The service is looked up on every call, so unregistering takes effect at once.
 */
public interface VehicleOwnershipAuthority {

	/** Whether the authority can judge changes now. Returning false refuses them like a missing one. */
	default boolean ready() {
		return true;
	}
}
