package net.tfminecraft.vehicleframework.api;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One vehicle's ownership as stored in SQLite and as held by its live object, kept apart so a
 * verifier can compare them.
 *
 * @param vehicleId         the vehicle
 * @param typeId            the vehicle type, from the live object when loaded
 * @param name              the vehicle name, from the live object when loaded
 * @param storedOwnerColumn the SQLite {@code owner} column, or {@code null} without a stored row
 * @param stored            the stored payload's fields; empty without a row or when the payload
 *                          cannot be read
 * @param live              the live object's fields; empty when the vehicle is not loaded
 */
public record OwnershipMeta(UUID vehicleId, String typeId, String name, String storedOwnerColumn,
		Optional<OwnershipValues> stored, Optional<OwnershipValues> live) {

	public OwnershipMeta {
		Objects.requireNonNull(vehicleId, "vehicleId");
		stored = stored == null ? Optional.empty() : stored;
		live = live == null ? Optional.empty() : live;
	}

	/** Whether a non-tombstoned SQLite row exists. */
	public boolean hasStoredRow() {
		return storedOwnerColumn != null;
	}

	/** Whether the vehicle is loaded. */
	public boolean isLive() {
		return live.isPresent();
	}

	/** The live fields when loaded, otherwise the stored ones. */
	public Optional<OwnershipValues> current() {
		return live.isPresent() ? live : stored;
	}
}
