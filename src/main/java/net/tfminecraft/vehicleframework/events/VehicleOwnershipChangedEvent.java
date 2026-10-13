package net.tfminecraft.vehicleframework.events;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.vehicleframework.api.OwnershipReason;
import net.tfminecraft.vehicleframework.api.OwnershipValues;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;

/**
 * Fired after an ownership write. Player actions fire it once per operation, also when the change
 * was cancelled or the write failed; system writes fire it only on success.
 */
public final class VehicleOwnershipChangedEvent extends Event {
	private static final HandlerList HANDLERS = new HandlerList();

	private final boolean success;
	private final OwnershipReason reason;
	private final UUID operationId;
	private final UUID vehicleId;
	private final ActiveVehicle vehicle;
	private final Player player;
	private final CharacterRef actor;
	private final boolean staff;
	private final CharacterRef target;
	private final String rawEntry;
	private final OwnershipValues previous;
	private final OwnershipValues current;

	public VehicleOwnershipChangedEvent(boolean success, OwnershipReason reason, UUID operationId, UUID vehicleId,
			ActiveVehicle vehicle, Player player, CharacterRef actor, boolean staff, CharacterRef target,
			String rawEntry, OwnershipValues previous, OwnershipValues current) {
		this.success = success;
		this.reason = Objects.requireNonNull(reason, "reason");
		this.operationId = Objects.requireNonNull(operationId, "operationId");
		this.vehicleId = vehicleId;
		this.vehicle = vehicle;
		this.player = player;
		this.actor = actor;
		this.staff = staff;
		this.target = target;
		this.rawEntry = rawEntry;
		this.previous = previous;
		this.current = current;
	}

	/** Whether the change was written. */
	public boolean isSuccess() {
		return success;
	}

	public OwnershipReason getReason() {
		return reason;
	}

	/** The operation ID of the matching {@link VehicleOwnershipChangeEvent}, or a fresh one for system writes. */
	public UUID getOperationId() {
		return operationId;
	}

	/** The vehicle changed; {@code null} only if its ID is not a UUID. */
	public UUID getVehicleId() {
		return vehicleId;
	}

	/** The loaded vehicle, or empty for a stored write. */
	public Optional<ActiveVehicle> getVehicle() {
		return Optional.ofNullable(vehicle);
	}

	/** The acting account; empty for system writes. */
	public Optional<Player> getPlayer() {
		return Optional.ofNullable(player);
	}

	public Optional<CharacterRef> getActor() {
		return Optional.ofNullable(actor);
	}

	public boolean isStaff() {
		return staff;
	}

	public Optional<CharacterRef> getTarget() {
		return Optional.ofNullable(target);
	}

	public Optional<String> getRawEntry() {
		return Optional.ofNullable(rawEntry);
	}

	/** The fields before the operation. */
	public OwnershipValues getPrevious() {
		return previous;
	}

	/** The fields now: the written ones on success, the unchanged ones otherwise. */
	public OwnershipValues getCurrent() {
		return current;
	}

	@Override
	public HandlerList getHandlers() {
		return HANDLERS;
	}

	public static HandlerList getHandlerList() {
		return HANDLERS;
	}
}
