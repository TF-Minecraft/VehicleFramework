package net.tfminecraft.vehicleframework.managers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.RegisteredServiceProvider;

import net.tfminecraft.vehicleframework.VFLogger;
import net.tfminecraft.vehicleframework.api.OwnershipMeta;
import net.tfminecraft.vehicleframework.api.QuarantinedVehicle;
import net.tfminecraft.vehicleframework.api.VehicleQuarantineSource;
import net.tfminecraft.vehicleframework.ownership.OwnershipStore;

/** {@code /vf admin quarantine}: vehicles the owner migration held back, from every registered source. */
public final class QuarantineCommand {

	private QuarantineCommand() {
	}

	public static void list(CommandSender sender) {
		List<RegisteredServiceProvider<VehicleQuarantineSource>> sources =
				new ArrayList<>(Bukkit.getServicesManager().getRegistrations(VehicleQuarantineSource.class));
		if (sources.isEmpty()) {
			sender.sendMessage("§7No quarantine list is registered. SimpleFactions registers one once it has migrated vehicle owners.");
			return;
		}
		List<QuarantinedVehicle> vehicles = new ArrayList<>();
		for (RegisteredServiceProvider<VehicleQuarantineSource> source : sources) {
			try {
				vehicles.addAll(source.getProvider().quarantinedVehicles());
			} catch (RuntimeException ex) {
				VFLogger.log("Quarantine list from " + source.getPlugin().getName() + " failed: " + ex);
				sender.sendMessage("§c" + source.getPlugin().getName() + " could not list its quarantine; see the console.");
			}
		}
		sender.sendMessage("§eQuarantined vehicles: §f" + vehicles.size());
		Optional<Map<UUID, OwnershipMeta>> known = known();
		for (QuarantinedVehicle vehicle : vehicles) {
			String label = known.isEmpty() ? " §8(metadata unavailable)"
					: Optional.ofNullable(known.get().get(vehicle.vehicleId()))
							.map(meta -> " §8" + meta.typeId() + " '" + meta.name() + "'")
							.orElse(" §8(not stored)");
			sender.sendMessage("§7" + vehicle.vehicleId() + label + " §f" + vehicle.owner() + " §7- " + vehicle.reason());
		}
	}

	/** Stored vehicles by ID, or empty when they could not be read. */
	private static Optional<Map<UUID, OwnershipMeta>> known() {
		try {
			return Optional.of(OwnershipStore.listOwnershipMeta().stream()
					.collect(Collectors.toMap(OwnershipMeta::vehicleId, Function.identity())));
		} catch (RuntimeException ex) {
			VFLogger.log("Could not read vehicles for the quarantine list: " + ex);
			return Optional.empty();
		}
	}
}
