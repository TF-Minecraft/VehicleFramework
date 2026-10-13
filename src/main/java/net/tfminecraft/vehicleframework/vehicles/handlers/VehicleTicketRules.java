package net.tfminecraft.vehicleframework.vehicles.handlers;

import net.tfminecraft.vehicleframework.data.OwnerData;
import net.tfminecraft.vehicleframework.enums.SeatType;
import net.tfminecraft.vehicleframework.ownership.AccessKey;

public final class VehicleTicketRules {
	private VehicleTicketRules() {
	}

	/** Names mode: owner and entries are account names. */
	public static boolean ownerOrWhitelisted(OwnerData data, String playerName) {
		return ownerOrWhitelisted(data, AccessKey.name(playerName));
	}

	/** A {@code null} key (no identity) matches nobody. */
	public static boolean ownerOrWhitelisted(OwnerData data, AccessKey key) {
		if (data == null || key == null) {
			return false;
		}
		if (key.owns(data.getOwner())) {
			return true;
		}
		if (data.getWhiteList() == null) {
			return false;
		}
		for (String entry : data.getWhiteList()) {
			if (key.matchesEntry(entry)) {
				return true;
			}
		}
		return false;
	}

	public static boolean mayEnter(OwnerData access, OwnerData tickets, String playerName, SeatType seat, boolean hasMatchingTicket) {
		return mayEnter(access, tickets, AccessKey.name(playerName), seat, hasMatchingTicket);
	}

	public static boolean mayEnter(OwnerData access, OwnerData tickets, AccessKey key, SeatType seat, boolean hasMatchingTicket) {
		return mayEnter(tickets.isTicketsEnabled(), seat, ownerOrWhitelisted(access, key), hasMatchingTicket);
	}

	public static boolean mayOpenSeatMenu(OwnerData access, String playerName, boolean hasMatchingTicket) {
		return mayOpenSeatMenu(access, access, AccessKey.name(playerName), hasMatchingTicket);
	}

	public static boolean mayOpenSeatMenu(OwnerData access, OwnerData tickets, String playerName, boolean hasMatchingTicket) {
		return mayOpenSeatMenu(access, tickets, AccessKey.name(playerName), hasMatchingTicket);
	}

	// Whitelists belong to the selected vehicle; coupled cars share only the locomotive's tickets.
	public static boolean mayOpenSeatMenu(OwnerData access, OwnerData tickets, AccessKey key, boolean hasMatchingTicket) {
		if (access == null) {
			return true;
		}
		if (!access.isWhiteListed()
				|| access.getOwner() == null
				|| access.getOwner().equalsIgnoreCase("none")) {
			return true;
		}
		if (ownerOrWhitelisted(access, key)) {
			return true;
		}
		return tickets != null && tickets.isTicketsEnabled() && hasMatchingTicket;
	}

	public static boolean mayEnter(boolean ticketsEnabled, SeatType seat, boolean ownerOrWhitelist, boolean hasMatchingTicket) {
		if (!ticketsEnabled) {
			return true;
		}
		if (ownerOrWhitelist) {
			return true;
		}
		if (seat == null || seat != SeatType.PASSENGER) {
			return true;
		}
		return hasMatchingTicket;
	}
}
