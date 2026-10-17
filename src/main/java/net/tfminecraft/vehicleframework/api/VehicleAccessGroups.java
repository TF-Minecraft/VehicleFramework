package net.tfminecraft.vehicleframework.api;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Answers group access for vehicles: registered in Bukkit's {@code ServicesManager} by the plugin
 * that knows factions and guilds (SimpleFactions). Without a ready service no group admits
 * anyone and the group switches are hidden. Only used in characters mode, on the main thread.
 */
public interface VehicleAccessGroups {

	/** Whether the service can answer now. Returning false admits nobody through groups. */
	default boolean ready() {
		return true;
	}

	/**
	 * Whether the rider's character belongs to this group of the vehicle.
	 *
	 * @param vehicleId the vehicle
	 * @param owner     its owner value ({@code char_<characterId>} or {@code char_none})
	 * @param rider     the rider's current character
	 */
	boolean admits(AccessGroup group, UUID vehicleId, String owner, UUID rider);

	/** The groups the vehicle admits whatever its switches say, such as a faction vehicle's faction. */
	default Set<AccessGroup> alwaysOpen(UUID vehicleId, String owner) {
		return Set.of();
	}

	/** The name of the faction or guild the group is counted from, or empty when there is none. */
	default Optional<String> describe(AccessGroup group, UUID vehicleId, String owner) {
		return Optional.empty();
	}
}
