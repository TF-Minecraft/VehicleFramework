package net.tfminecraft.vehicleframework.api;

/**
 * How vehicle owners are identified, read once at enable from the {@code ownership-identity}
 * config key.
 */
public enum OwnershipIdentity {
	/** Owners and whitelist entries are characters ({@code char_<characterId>}). */
	CHARACTERS,
	/** Owners and whitelist entries are account names ({@code player_<name>}); no RPCharacters. */
	NAMES,
	/**
	 * The key is missing while RPCharacters is installed, or holds an unknown value. Every
	 * ownership feature is refused until the key is set and the server restarts.
	 */
	DISABLED
}
