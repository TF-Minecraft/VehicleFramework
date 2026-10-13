package net.tfminecraft.vehicleframework.api;

import java.util.List;

/**
 * Lists vehicles held back from the owner migration, for {@code /vf admin quarantine}. Register
 * implementations in Bukkit's {@code ServicesManager}; every registration is listed. Called on the
 * main thread.
 */
public interface VehicleQuarantineSource {

	/** The quarantined vehicles, or an empty list. */
	List<QuarantinedVehicle> quarantinedVehicles();
}
