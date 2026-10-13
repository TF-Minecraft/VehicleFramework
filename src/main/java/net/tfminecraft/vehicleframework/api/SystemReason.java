package net.tfminecraft.vehicleframework.api;

/**
 * Reasons accepted by {@link VehicleFrameworkApi#setOwnership}. Each decides what happens to the
 * whitelist entries and the whitelist flag; tickets are never changed.
 *
 * <table>
 * <caption>Whitelist disposition</caption>
 * <tr><th>Reason</th><th>Entries</th><th>Flag</th></tr>
 * <tr><td>RESTAMP</td><td>kept</td><td>kept</td></tr>
 * <tr><td>MIGRATE</td><td>as seeded ({@link OwnerChange#whitelist()}, kept when null)</td><td>kept</td></tr>
 * <tr><td>CONSTRUCT</td><td>emptied</td><td>on if {@code allow-whitelist} and {@code whitelisted-by-default}</td></tr>
 * <tr><td>RELEASE, HANDOVER</td><td>emptied</td><td>off</td></tr>
 * <tr><td>TRANSFER</td><td>emptied</td><td>kept</td></tr>
 * </table>
 */
public enum SystemReason {
	MIGRATE(OwnershipReason.MIGRATE),
	RESTAMP(OwnershipReason.RESTAMP),
	RELEASE(OwnershipReason.RELEASE),
	HANDOVER(OwnershipReason.HANDOVER),
	CONSTRUCT(OwnershipReason.CONSTRUCT),
	TRANSFER(OwnershipReason.TRANSFER);

	private final OwnershipReason reason;

	SystemReason(OwnershipReason reason) {
		this.reason = reason;
	}

	/** The reason carried by {@link net.tfminecraft.vehicleframework.events.VehicleOwnershipChangedEvent}. */
	public OwnershipReason reason() {
		return reason;
	}
}
