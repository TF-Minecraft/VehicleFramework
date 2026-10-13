package net.tfminecraft.vehicleframework.ownership;

import java.util.UUID;

import net.tfminecraft.vehicleframework.api.VehicleOwners;

/**
 * Who a player counts as for owner and whitelist checks: a character in characters mode, an
 * account name in names mode.
 */
public record AccessKey(String ownerValue, UUID characterId, String legacyName) {

	public static AccessKey character(UUID characterId) {
		return new AccessKey(VehicleOwners.character(characterId), characterId, null);
	}

	/** Names mode; {@code null} for a {@code null} name. */
	public static AccessKey name(String playerName) {
		return playerName == null ? null : new AccessKey(VehicleOwners.LEGACY_PREFIX + playerName, null, playerName);
	}

	public boolean owns(String owner) {
		return owner != null && owner.equalsIgnoreCase(ownerValue);
	}

	/** Character entries match by character; legacy entries match account names in names mode only. */
	public boolean matchesEntry(String entry) {
		if (entry == null) {
			return false;
		}
		if (characterId != null) {
			return VehicleOwners.parseWhitelistEntry(entry)
					.map(parsed -> parsed.character().characterId().equals(characterId))
					.orElse(false);
		}
		return entry.equalsIgnoreCase(ownerValue) || entry.equalsIgnoreCase(legacyName);
	}
}
