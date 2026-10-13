package net.tfminecraft.vehicleframework.api;

/** Why a vehicle's owner, whitelist or tickets changed. */
public enum OwnershipReason {
	/** A player claimed an unowned vehicle, including a staff takeover of an unowned one. */
	CLAIM,
	/** The owner removed their ownership. */
	CLEAR,
	/** Staff took over a vehicle that already had an owner. */
	TAKEOVER,
	WHITELIST_ADD,
	WHITELIST_REMOVE,
	WHITELIST_TOGGLE,
	/** Tickets toggled on the vehicle's ticket source (the locomotive of a coupled car). */
	TICKETS_TOGGLE,
	MIGRATE,
	RESTAMP,
	RELEASE,
	HANDOVER,
	CONSTRUCT,
	TRANSFER;

	/** Whether this reason comes from {@link VehicleFrameworkApi#setOwnership}. */
	public boolean isSystem() {
		return ordinal() >= MIGRATE.ordinal();
	}
}
