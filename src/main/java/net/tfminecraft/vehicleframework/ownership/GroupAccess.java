package net.tfminecraft.vehicleframework.ownership;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;

import net.tfminecraft.vehicleframework.VFLogger;
import net.tfminecraft.vehicleframework.api.AccessGroup;
import net.tfminecraft.vehicleframework.api.VehicleAccessGroups;
import net.tfminecraft.vehicleframework.api.VehicleOwners;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;

/**
 * Group access through the registered {@link VehicleAccessGroups} service. Only characters mode
 * uses it; an unowned vehicle, a missing or unready service, or a service error admits nobody.
 */
public final class GroupAccess {

	private GroupAccess() {
	}

	/** The ready service in characters mode. */
	public static Optional<VehicleAccessGroups> service() {
		if (!OwnershipMode.characters()) {
			return Optional.empty();
		}
		try {
			VehicleAccessGroups service = Bukkit.getServicesManager().load(VehicleAccessGroups.class);
			return service != null && service.ready() ? Optional.of(service) : Optional.empty();
		} catch (RuntimeException | LinkageError error) {
			VFLogger.log("Vehicle group access is unavailable: " + error);
			return Optional.empty();
		}
	}

	/** The groups the vehicle admits whatever its switches say. */
	public static Set<AccessGroup> alwaysOpen(ActiveVehicle vehicle) {
		Optional<Owned> owned = owned(vehicle);
		Optional<VehicleAccessGroups> service = service();
		if (owned.isEmpty() || service.isEmpty()) {
			return Set.of();
		}
		return fixed(service.get(), owned.get());
	}

	/** Whether a group the vehicle admits, by switch or always, has the rider's character in it. */
	public static boolean admits(ActiveVehicle vehicle, AccessKey key) {
		if (key == null || key.characterId() == null) {
			return false;
		}
		Optional<Owned> owned = owned(vehicle);
		Optional<VehicleAccessGroups> service = service();
		if (owned.isEmpty() || service.isEmpty()) {
			return false;
		}
		Set<AccessGroup> groups = EnumSet.noneOf(AccessGroup.class);
		groups.addAll(vehicle.getOwnerData().getAccessGroups());
		groups.addAll(fixed(service.get(), owned.get()));
		for (AccessGroup group : groups) {
			try {
				if (service.get().admits(group, owned.get().id(), owned.get().owner(), key.characterId())) {
					return true;
				}
			} catch (RuntimeException | LinkageError error) {
				VFLogger.log("Could not check " + group + " access to vehicle " + vehicle.getUUID() + ": " + error);
			}
		}
		return false;
	}

	/** The faction or guild a group is counted from, for the menu. */
	public static Optional<String> describe(ActiveVehicle vehicle, AccessGroup group) {
		Optional<Owned> owned = owned(vehicle);
		Optional<VehicleAccessGroups> service = service();
		if (owned.isEmpty() || service.isEmpty()) {
			return Optional.empty();
		}
		try {
			Optional<String> name = service.get().describe(group, owned.get().id(), owned.get().owner());
			return name == null ? Optional.empty() : name.filter(text -> !text.isBlank());
		} catch (RuntimeException | LinkageError error) {
			VFLogger.log("Could not name the " + group + " of vehicle " + vehicle.getUUID() + ": " + error);
			return Optional.empty();
		}
	}

	private record Owned(UUID id, String owner) {
	}

	private static Set<AccessGroup> fixed(VehicleAccessGroups service, Owned owned) {
		try {
			Set<AccessGroup> fixed = service.alwaysOpen(owned.id(), owned.owner());
			return fixed == null ? Set.of() : Set.copyOf(fixed);
		} catch (RuntimeException | LinkageError error) {
			VFLogger.log("Could not read the fixed groups of vehicle " + owned.id() + ": " + error);
			return Set.of();
		}
	}

	private static Optional<Owned> owned(ActiveVehicle vehicle) {
		if (vehicle == null || vehicle.getOwnerData() == null) {
			return Optional.empty();
		}
		String owner = vehicle.getOwnerData().getOwner();
		if (VehicleOwners.isUnowned(owner)) {
			return Optional.empty();
		}
		return OwnershipStore.parseUuid(vehicle.getUUID()).map(id -> new Owned(id, owner));
	}
}
