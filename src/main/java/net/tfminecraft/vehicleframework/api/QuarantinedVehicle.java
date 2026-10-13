package net.tfminecraft.vehicleframework.api;

import java.util.Objects;
import java.util.UUID;

/**
 * A vehicle whose owner could not be migrated and keeps its legacy value.
 *
 * @param vehicleId the vehicle
 * @param owner     its current owner value
 * @param reason    a short staff-facing reason
 */
public record QuarantinedVehicle(UUID vehicleId, String owner, String reason) {

	public QuarantinedVehicle {
		Objects.requireNonNull(vehicleId, "vehicleId");
		owner = owner == null ? "" : owner;
		reason = reason == null ? "" : reason;
	}
}
