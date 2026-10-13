package net.tfminecraft.vehicleframework.events;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.vehicleframework.api.OwnershipReason;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;

/**
 * Fired before a player's ownership change is written. Cancel to refuse it; a
 * {@link VehicleOwnershipChangedEvent} with {@code success == false} and the same operation ID
 * follows either way.
 */
public final class VehicleOwnershipChangeEvent extends Event implements Cancellable {
	private static final HandlerList HANDLERS = new HandlerList();

	private final Player player;
	private final ActiveVehicle vehicle;
	private final OwnershipReason reason;
	private final UUID operationId;
	private final CharacterRef actor;
	private final boolean staff;
	private final CharacterRef target;
	private final String rawEntry;
	private final String previousOwner;
	private final String newOwner;
	private boolean cancelled;

	public VehicleOwnershipChangeEvent(Player player, ActiveVehicle vehicle, OwnershipReason reason, UUID operationId,
			CharacterRef actor, boolean staff, CharacterRef target, String rawEntry, String previousOwner,
			String newOwner) {
		this.player = Objects.requireNonNull(player, "player");
		this.vehicle = Objects.requireNonNull(vehicle, "vehicle");
		this.reason = Objects.requireNonNull(reason, "reason");
		this.operationId = Objects.requireNonNull(operationId, "operationId");
		this.actor = actor;
		this.staff = staff;
		this.target = target;
		this.rawEntry = rawEntry;
		this.previousOwner = previousOwner;
		this.newOwner = newOwner;
	}

	/** The acting account. */
	public Player getPlayer() {
		return player;
	}

	/** The vehicle actually changed; for a tickets toggle, the ticket source. */
	public ActiveVehicle getVehicle() {
		return vehicle;
	}

	public OwnershipReason getReason() {
		return reason;
	}

	/** Minted by VehicleFramework for this action. */
	public UUID getOperationId() {
		return operationId;
	}

	/** The acting character; empty in names mode. */
	public Optional<CharacterRef> getActor() {
		return Optional.ofNullable(actor);
	}

	/** Whether the actor holds {@code vf.admin}. */
	public boolean isStaff() {
		return staff;
	}

	/** The character gaining ownership or added to or removed from the whitelist, if any. */
	public Optional<CharacterRef> getTarget() {
		return Optional.ofNullable(target);
	}

	/** The whitelist entry added or removed, in stored form. */
	public Optional<String> getRawEntry() {
		return Optional.ofNullable(rawEntry);
	}

	public String getPreviousOwner() {
		return previousOwner;
	}

	/** The owner after the change; equal to the previous owner for whitelist and ticket changes. */
	public String getNewOwner() {
		return newOwner;
	}

	@Override
	public boolean isCancelled() {
		return cancelled;
	}

	@Override
	public void setCancelled(boolean cancelled) {
		this.cancelled = cancelled;
	}

	@Override
	public HandlerList getHandlers() {
		return HANDLERS;
	}

	public static HandlerList getHandlerList() {
		return HANDLERS;
	}
}
