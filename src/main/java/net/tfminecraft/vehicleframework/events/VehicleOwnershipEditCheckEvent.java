package net.tfminecraft.vehicleframework.events;

import java.util.Objects;
import java.util.Optional;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.vehicleframework.api.OwnershipReason;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;

/**
 * Asks whether a player may use an ownership control. Fired to decide which buttons to show and
 * again on every click. Listeners must not change anything; deny to hide or refuse the control.
 */
public final class VehicleOwnershipEditCheckEvent extends Event {
	private static final HandlerList HANDLERS = new HandlerList();

	private final Player player;
	private final ActiveVehicle vehicle;
	private final OwnershipReason reason;
	private final CharacterRef actor;
	private final boolean staff;
	private boolean allowed = true;

	public VehicleOwnershipEditCheckEvent(Player player, ActiveVehicle vehicle, OwnershipReason reason,
			CharacterRef actor, boolean staff) {
		this.player = Objects.requireNonNull(player, "player");
		this.vehicle = Objects.requireNonNull(vehicle, "vehicle");
		this.reason = reason;
		this.actor = actor;
		this.staff = staff;
	}

	public Player getPlayer() {
		return player;
	}

	/** The vehicle the control changes; for the tickets toggle, the ticket source. */
	public ActiveVehicle getVehicle() {
		return vehicle;
	}

	/** The change the control makes, or empty when opening the ownership menu. */
	public Optional<OwnershipReason> getReason() {
		return Optional.ofNullable(reason);
	}

	/** Whether this check opens the ownership menu. */
	public boolean isMenu() {
		return reason == null;
	}

	public Optional<CharacterRef> getActor() {
		return Optional.ofNullable(actor);
	}

	public boolean isStaff() {
		return staff;
	}

	public boolean isAllowed() {
		return allowed;
	}

	public void deny() {
		allowed = false;
	}

	@Override
	public HandlerList getHandlers() {
		return HANDLERS;
	}

	public static HandlerList getHandlerList() {
		return HANDLERS;
	}
}
