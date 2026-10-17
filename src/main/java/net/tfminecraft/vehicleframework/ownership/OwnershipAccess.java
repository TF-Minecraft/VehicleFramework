package net.tfminecraft.vehicleframework.ownership;

import java.util.Optional;

import org.bukkit.entity.Player;

import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.vehicleframework.api.OwnershipIdentity;
import net.tfminecraft.vehicleframework.data.OwnerData;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.handlers.VehicleTicketRules;

/** Resolves players to owner identities. Characters mode asks the provider on every call. */
public final class OwnershipAccess {

	private OwnershipAccess() {
	}

	/** The player's identity, or {@code null} when ownership is disabled or no character is current. */
	public static AccessKey key(Player player) {
		if (player == null) {
			return null;
		}
		OwnershipIdentity identity = OwnershipMode.identity();
		if (identity == OwnershipIdentity.NAMES) {
			return AccessKey.name(player.getName());
		}
		if (identity == OwnershipIdentity.CHARACTERS) {
			return session(player).map(session -> AccessKey.character(session.ref().characterId())).orElse(null);
		}
		return null;
	}

	/** The current session in characters mode; empty otherwise. */
	public static Optional<CharacterSession> session(Player player) {
		if (!OwnershipMode.characters()) {
			return Optional.empty();
		}
		return CharacterLookup.session(player);
	}

	/** The owner value the player owns vehicles under. */
	public static Optional<String> ownerValue(Player player) {
		return Optional.ofNullable(key(player)).map(AccessKey::ownerValue);
	}

	public static boolean isOwner(Player player, OwnerData data) {
		AccessKey key = key(player);
		return key != null && data != null && key.owns(data.getOwner());
	}

	/** Whether the key owns the vehicle, is on its whitelist, or is in a group it admits. */
	public static boolean admits(ActiveVehicle vehicle, AccessKey key) {
		return VehicleTicketRules.ownerOrWhitelisted(vehicle.getOwnerData(), key) || GroupAccess.admits(vehicle, key);
	}
}
