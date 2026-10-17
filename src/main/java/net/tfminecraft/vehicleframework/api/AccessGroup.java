package net.tfminecraft.vehicleframework.api;

import java.util.Locale;
import java.util.Optional;

/**
 * A group a vehicle can admit as a whole, on top of its whitelist. Membership is decided by the
 * registered {@link VehicleAccessGroups} service, against the owner's current groups.
 */
public enum AccessGroup {
	/** Members of the owner's faction. */
	FACTION(OwnershipReason.FACTION_ACCESS_TOGGLE),
	/** Members of the owner's guild. */
	GUILD(OwnershipReason.GUILD_ACCESS_TOGGLE),
	/** Members of factions allied with the owner's faction. */
	ALLY(OwnershipReason.ALLY_ACCESS_TOGGLE),
	/** Members of the direct vassals of the owner's faction. */
	VASSAL(OwnershipReason.VASSAL_ACCESS_TOGGLE);

	private final OwnershipReason toggle;

	AccessGroup(OwnershipReason toggle) {
		this.toggle = toggle;
	}

	/** The reason carried by the ownership events when this group's switch is flipped. */
	public OwnershipReason toggle() {
		return toggle;
	}

	/** The group stored under this name, or empty for anything else. */
	public static Optional<AccessGroup> parse(Object stored) {
		if (stored == null) {
			return Optional.empty();
		}
		try {
			return Optional.of(valueOf(String.valueOf(stored).trim().toUpperCase(Locale.ROOT)));
		} catch (IllegalArgumentException unknown) {
			return Optional.empty();
		}
	}
}
