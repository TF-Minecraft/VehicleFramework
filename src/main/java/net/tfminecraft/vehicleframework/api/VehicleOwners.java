package net.tfminecraft.vehicleframework.api;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import net.tfminecraft.tlibs.character.CharacterRef;

/**
 * Owner values and whitelist entries.
 *
 * <table>
 * <caption>Owner values</caption>
 * <tr><td>{@code char_<characterId>}</td><td>owned by that character</td></tr>
 * <tr><td>{@code none}</td><td>unowned, claimable</td></tr>
 * <tr><td>{@code char_none}</td><td>faction vehicle without a leader character; matches nobody and
 * can never be claimed</td></tr>
 * <tr><td>{@code player_<name>}</td><td>legacy account name; inert in characters mode</td></tr>
 * </table>
 *
 * <p>Character whitelist entries are {@code char_<characterId>;<accountId>;<name>}, where the name
 * is the one captured when the entry was added.
 */
public final class VehicleOwners {
	public static final String NONE = "none";
	public static final String NO_CHARACTER = "char_none";
	public static final String CHARACTER_PREFIX = "char_";
	public static final String LEGACY_PREFIX = "player_";

	private static final String SEPARATOR = ";";
	private static final int UUID_LENGTH = 36;

	private VehicleOwners() {
	}

	/** The owner value for a character. */
	public static String character(UUID characterId) {
		return CHARACTER_PREFIX + Objects.requireNonNull(characterId, "characterId");
	}

	/** The character of a {@code char_<uuid>} owner value; empty for anything else. */
	public static Optional<UUID> characterId(String owner) {
		if (owner == null || !hasPrefix(owner, CHARACTER_PREFIX)) {
			return Optional.empty();
		}
		return uuid(owner.substring(CHARACTER_PREFIX.length()));
	}

	/** Whether the vehicle has no owner and can be claimed. */
	public static boolean isUnowned(String owner) {
		return owner == null || owner.isBlank() || owner.equalsIgnoreCase(NONE);
	}

	/** Whether the value names a character. */
	public static boolean isCharacterOwner(String owner) {
		return characterId(owner).isPresent();
	}

	/** Whether the value is a legacy {@code player_<name>} owner other than {@code player_none}. */
	public static boolean isLegacyOwner(String owner) {
		return owner != null
				&& hasPrefix(owner, LEGACY_PREFIX)
				&& owner.length() > LEGACY_PREFIX.length()
				&& !owner.equalsIgnoreCase(LEGACY_PREFIX + NONE);
	}

	/** Whether a single person owns the vehicle: a character or a legacy account name. */
	public static boolean isPersonalOwner(String owner) {
		return isCharacterOwner(owner) || isLegacyOwner(owner);
	}

	/**
	 * The canonical form of a value that characters mode may write ({@code none}, {@code char_none}
	 * or {@code char_<uuid>}), or empty for anything else.
	 */
	public static Optional<String> canonical(String owner) {
		if (owner == null) {
			return Optional.empty();
		}
		if (owner.equalsIgnoreCase(NONE)) {
			return Optional.of(NONE);
		}
		if (owner.equalsIgnoreCase(NO_CHARACTER)) {
			return Optional.of(NO_CHARACTER);
		}
		return characterId(owner).map(VehicleOwners::character);
	}

	/** The stored whitelist entry for a character. */
	public static String whitelistEntry(CharacterRef character, String name) {
		Objects.requireNonNull(character, "character");
		return character(character.characterId()) + SEPARATOR + character.ownerId() + SEPARATOR
				+ (name == null ? "" : name);
	}

	/** The character entry stored in a raw whitelist value; empty for legacy or unreadable values. */
	public static Optional<WhitelistEntry> parseWhitelistEntry(String raw) {
		if (raw == null || !hasPrefix(raw, CHARACTER_PREFIX)) {
			return Optional.empty();
		}
		String[] parts = raw.substring(CHARACTER_PREFIX.length()).split(SEPARATOR, 3);
		if (parts.length < 3) {
			return Optional.empty();
		}
		Optional<UUID> characterId = uuid(parts[0]);
		Optional<UUID> ownerId = uuid(parts[1]);
		if (characterId.isEmpty() || ownerId.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new WhitelistEntry(new CharacterRef(ownerId.get(), characterId.get()), parts[2]));
	}

	private static boolean hasPrefix(String value, String prefix) {
		return value.regionMatches(true, 0, prefix, 0, prefix.length());
	}

	private static Optional<UUID> uuid(String value) {
		if (value.length() != UUID_LENGTH) {
			return Optional.empty();
		}
		try {
			UUID parsed = UUID.fromString(value);
			return parsed.toString().equals(value.toLowerCase(Locale.ROOT)) ? Optional.of(parsed) : Optional.empty();
		} catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
	}
}
