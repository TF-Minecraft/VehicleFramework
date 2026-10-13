package net.tfminecraft.vehicleframework.ownership;

import java.util.Optional;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.vehicleframework.api.VehicleOwners;

/** Names shown to players. Characters show their real name; staff also see the account. */
public final class OwnerNames {

	private OwnerNames() {
	}

	/** The name others see for an online player. */
	public static String player(Player player) {
		return OwnershipMode.characters() ? CharacterLookup.displayName(player) : player.getName();
	}

	/** An owner value as text, such as "Kira" or, for staff, "Kira (Steve)". */
	public static String owner(String owner, boolean staff) {
		if (VehicleOwners.isUnowned(owner)) {
			return "nobody";
		}
		if (owner.equalsIgnoreCase(VehicleOwners.NO_CHARACTER)) {
			return "no leader";
		}
		Optional<UUID> characterId = VehicleOwners.characterId(owner);
		if (characterId.isPresent()) {
			Optional<CharacterInfo> info = CharacterLookup.info(characterId.get());
			if (info.isEmpty()) {
				return "an unknown character";
			}
			String name = info.get().name();
			return staff ? name + " (" + account(info.get().ref().ownerId()) + ")" : name;
		}
		return legacy(owner);
	}

	/** An account's last known name, or its UUID. */
	public static String account(UUID accountId) {
		String name = Bukkit.getOfflinePlayer(accountId).getName();
		return name == null ? accountId.toString() : name;
	}

	/** A legacy value without its {@code player_} prefix. */
	public static String legacy(String value) {
		return value.regionMatches(true, 0, VehicleOwners.LEGACY_PREFIX, 0, VehicleOwners.LEGACY_PREFIX.length())
				? value.substring(VehicleOwners.LEGACY_PREFIX.length())
				: value;
	}
}
