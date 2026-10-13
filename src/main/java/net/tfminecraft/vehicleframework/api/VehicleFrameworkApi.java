package net.tfminecraft.vehicleframework.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.tfminecraft.vehicleframework.ownership.OwnershipMode;
import net.tfminecraft.vehicleframework.ownership.OwnershipStore;

/**
 * Public ownership API for other plugins. Every method must be called on the main thread.
 */
public final class VehicleFrameworkApi {

	/** Outcome of {@link #setOwnership}. */
	public enum Result {
		OK,
		/** No loaded vehicle and no stored, non-tombstoned row has this ID. */
		NOT_FOUND,
		/** The write failed; nothing changed. */
		SAVE_FAILED,
		/** The identity mode is not {@link OwnershipIdentity#CHARACTERS}. */
		REFUSED_MODE
	}

	private VehicleFrameworkApi() {
	}

	/** The identity mode read at enable. */
	public static OwnershipIdentity ownershipIdentity() {
		return OwnershipMode.identity();
	}

	/**
	 * Every stored row except tombstones, plus loaded vehicles without a row, with stored and live
	 * fields kept apart.
	 *
	 * @throws IllegalStateException if SQLite is not open
	 * @throws RuntimeException      if the query fails
	 */
	public static List<OwnershipMeta> listOwnershipMeta() {
		return OwnershipStore.listOwnershipMeta();
	}

	/**
	 * One vehicle's stored and live fields, or empty if it is neither loaded nor stored.
	 *
	 * @throws IllegalStateException if SQLite is not open
	 * @throws RuntimeException      if the query fails
	 */
	public static Optional<OwnershipMeta> readOwnershipMeta(UUID vehicleId) {
		return OwnershipStore.readOwnershipMeta(vehicleId);
	}

	/**
	 * Sets the owner for a system reason. A loaded vehicle is authoritative: its fields change and
	 * it is saved at once, and a failed save puts the old fields back. An unloaded vehicle has its
	 * SQLite {@code owner} column and payload rewritten in one transaction. The whitelist follows
	 * the reason (see {@link SystemReason}); tickets never change.
	 * {@link net.tfminecraft.vehicleframework.events.VehicleOwnershipChangedEvent} fires only on
	 * {@link Result#OK}.
	 *
	 * @throws IllegalArgumentException if {@code change} has a whitelist and the reason is not MIGRATE
	 */
	public static Result setOwnership(UUID vehicleId, OwnerChange change, SystemReason reason) {
		return OwnershipStore.setOwnership(vehicleId, change, reason);
	}
}
