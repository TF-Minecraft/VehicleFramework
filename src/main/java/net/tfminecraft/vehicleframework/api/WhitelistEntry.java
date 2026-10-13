package net.tfminecraft.vehicleframework.api;

import java.util.Objects;

import net.tfminecraft.tlibs.character.CharacterRef;

/**
 * A character whitelist entry: the character and the name captured when it was added.
 */
public record WhitelistEntry(CharacterRef character, String name) {

	public WhitelistEntry {
		Objects.requireNonNull(character, "character");
		name = name == null ? "" : name;
	}

	/** The stored form, {@code char_<characterId>;<accountId>;<name>}. */
	public String encode() {
		return VehicleOwners.whitelistEntry(character, name);
	}
}
