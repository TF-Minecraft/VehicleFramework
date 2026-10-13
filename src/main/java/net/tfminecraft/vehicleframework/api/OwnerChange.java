package net.tfminecraft.vehicleframework.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The owner a system write sets, plus the seeded whitelist entries for {@link SystemReason#MIGRATE}.
 *
 * @param owner     {@code none}, {@code char_none} or {@code char_<characterId>}, stored in canonical
 *                  form
 * @param whitelist the entries to store; only accepted with {@code MIGRATE}, {@code null} keeps the
 *                  current entries
 */
public record OwnerChange(String owner, List<String> whitelist) {

	public OwnerChange {
		Objects.requireNonNull(owner, "owner");
		String given = owner;
		owner = VehicleOwners.canonical(given).orElseThrow(
				() -> new IllegalArgumentException("Not a character-mode owner value: " + given));
		whitelist = whitelist == null ? null : List.copyOf(whitelist);
	}

	/** Owned by the character. */
	public static OwnerChange character(UUID characterId) {
		return new OwnerChange(VehicleOwners.character(characterId), null);
	}

	/** A faction vehicle without a leader character; nobody matches it and nobody can claim it. */
	public static OwnerChange noCharacter() {
		return new OwnerChange(VehicleOwners.NO_CHARACTER, null);
	}

	/** Unowned and claimable. */
	public static OwnerChange unowned() {
		return new OwnerChange(VehicleOwners.NONE, null);
	}

	/** The migration seed: owner plus the decided whitelist entries. */
	public static OwnerChange seeded(String owner, List<String> whitelist) {
		return new OwnerChange(owner, Objects.requireNonNull(whitelist, "whitelist"));
	}
}
