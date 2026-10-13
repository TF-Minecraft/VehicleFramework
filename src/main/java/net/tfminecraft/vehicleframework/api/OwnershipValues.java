package net.tfminecraft.vehicleframework.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable copy of a vehicle's ownership fields.
 *
 * @param owner          the owner value, {@code none} when blank
 * @param whitelist      raw whitelist entries in stored order
 * @param whitelisted    whether the whitelist is enforced
 * @param ticketsEnabled whether passenger seats need a ticket
 * @param ticketId       the current ticket ID, or {@code null}
 */
public record OwnershipValues(String owner, List<String> whitelist, boolean whitelisted, boolean ticketsEnabled,
		String ticketId) {

	public OwnershipValues {
		owner = owner == null || owner.isBlank() ? VehicleOwners.NONE : owner;
		List<String> entries = new ArrayList<>();
		if (whitelist != null) {
			whitelist.stream().filter(Objects::nonNull).forEach(entries::add);
		}
		whitelist = List.copyOf(entries);
	}
}
