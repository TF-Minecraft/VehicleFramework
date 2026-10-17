package net.tfminecraft.vehicleframework.api;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable copy of a vehicle's ownership fields.
 *
 * @param owner          the owner value, {@code none} when blank
 * @param whitelist      raw whitelist entries in stored order
 * @param whitelisted    whether the whitelist is enforced
 * @param ticketsEnabled whether passenger seats need a ticket
 * @param ticketId       the current ticket ID, or {@code null}
 * @param accessGroups   the group switches that are on
 */
public record OwnershipValues(String owner, List<String> whitelist, boolean whitelisted, boolean ticketsEnabled,
		String ticketId, Set<AccessGroup> accessGroups) {

	public OwnershipValues {
		owner = owner == null || owner.isBlank() ? VehicleOwners.NONE : owner;
		List<String> entries = new ArrayList<>();
		if (whitelist != null) {
			whitelist.stream().filter(Objects::nonNull).forEach(entries::add);
		}
		whitelist = List.copyOf(entries);
		Set<AccessGroup> groups = EnumSet.noneOf(AccessGroup.class);
		if (accessGroups != null) {
			accessGroups.stream().filter(Objects::nonNull).forEach(groups::add);
		}
		accessGroups = Set.copyOf(groups);
	}

	/** Values with every group switch off. */
	public OwnershipValues(String owner, List<String> whitelist, boolean whitelisted, boolean ticketsEnabled,
			String ticketId) {
		this(owner, whitelist, whitelisted, ticketsEnabled, ticketId, Set.of());
	}
}
