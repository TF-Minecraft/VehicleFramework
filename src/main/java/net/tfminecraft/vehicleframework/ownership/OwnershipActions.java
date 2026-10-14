package net.tfminecraft.vehicleframework.ownership;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.vehicleframework.api.OwnershipIdentity;
import net.tfminecraft.vehicleframework.api.OwnershipReason;
import net.tfminecraft.vehicleframework.api.OwnershipValues;
import net.tfminecraft.vehicleframework.api.VehicleFrameworkApi.Result;
import net.tfminecraft.vehicleframework.api.VehicleOwners;
import net.tfminecraft.vehicleframework.api.VehicleOwnershipAuthority;
import net.tfminecraft.vehicleframework.data.OwnerData;
import net.tfminecraft.vehicleframework.events.VehicleOwnershipChangeEvent;
import net.tfminecraft.vehicleframework.events.VehicleOwnershipChangedEvent;
import net.tfminecraft.vehicleframework.events.VehicleOwnershipEditCheckEvent;
import net.tfminecraft.vehicleframework.permissions.Permissions;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;

/** Checks and the write pipeline for ownership changes made by players. */
public final class OwnershipActions {
	public static final String AUTHORITY_PLUGIN = "SimpleFactions";

	public static final String NEED_CHARACTER = "§cYou need an active character to do that.";
	public static final String DISABLED = "§cVehicle ownership is turned off on this server.";
	public static final String UNAVAILABLE = "§cVehicle ownership changes are unavailable right now.";
	public static final String NOT_OWNER = "§cYou do not own this vehicle.";
	public static final String NOT_ALLOWED = "§cYou cannot change that on this vehicle.";
	public static final String SAVE_FAILED = "§cCould not save the change. Nothing was changed.";

	private OwnershipActions() {
	}

	/** Who is acting: the account, its current character in characters mode, and the staff flag. */
	public record Actor(Player player, CharacterSession session, boolean staff) {
		public CharacterRef ref() {
			return session == null ? null : session.ref();
		}

		public AccessKey key() {
			return session == null ? AccessKey.name(player.getName()) : AccessKey.character(session.ref().characterId());
		}

		/** The owner value this actor would own under. */
		public String ownerValue() {
			return key().ownerValue();
		}
	}

	/** The actor, or the refusal message: ownership disabled, or no current character. */
	public static Outcome<Actor> actor(Player player) {
		OwnershipIdentity identity = OwnershipMode.identity();
		if (identity == OwnershipIdentity.DISABLED) {
			return Outcome.refused(DISABLED);
		}
		boolean staff = Permissions.isAdmin(player);
		if (identity == OwnershipIdentity.NAMES) {
			return Outcome.of(new Actor(player, null, staff));
		}
		Optional<CharacterSession> session = CharacterLookup.session(player);
		return session.isPresent() ? Outcome.of(new Actor(player, session.get(), staff)) : Outcome.refused(NEED_CHARACTER);
	}

	/**
	 * Whether non-staff changes may go ahead: in characters mode, while SimpleFactions is installed,
	 * a ready {@link VehicleOwnershipAuthority} must be registered.
	 */
	public static boolean authorityAllows(Actor actor) {
		if (actor.staff() || !OwnershipMode.characters()) {
			return true;
		}
		if (Bukkit.getPluginManager().getPlugin(AUTHORITY_PLUGIN) == null) {
			return true;
		}
		VehicleOwnershipAuthority authority = Bukkit.getServicesManager().load(VehicleOwnershipAuthority.class);
		return authority != null && authority.ready();
	}

	/** Whether the actor owns the vehicle or is staff. */
	public static boolean controls(Actor actor, ActiveVehicle vehicle) {
		return actor.staff() || actor.key().owns(vehicle.getOwnerData().getOwner());
	}

	/**
	 * Why the player may not make this change to the vehicle (for the tickets toggle, the ticket
	 * source), or empty if they may. Fires {@link VehicleOwnershipEditCheckEvent}.
	 */
	public static Optional<String> refusal(Player player, ActiveVehicle vehicle, OwnershipReason reason) {
		Outcome<Actor> actor = actor(player);
		if (actor.refusal() != null) {
			return Optional.of(actor.refusal());
		}
		if (!controls(actor.value(), vehicle)) {
			return Optional.of(NOT_OWNER);
		}
		if (!authorityAllows(actor.value())) {
			return Optional.of(UNAVAILABLE);
		}
		return editCheck(actor.value(), vehicle, reason) ? Optional.empty() : Optional.of(NOT_ALLOWED);
	}

	public static boolean allowed(Player player, ActiveVehicle vehicle, OwnershipReason reason) {
		return refusal(player, vehicle, reason).isEmpty();
	}

	/**
	 * Whether the player sees the ownership menu: the owner, or staff on an owned vehicle, unless an
	 * edit check denies it.
	 */
	public static boolean mayOpenMenu(Player player, ActiveVehicle vehicle) {
		Outcome<Actor> actor = actor(player);
		if (actor.refusal() != null) {
			return false;
		}
		String owner = vehicle.getOwnerData().getOwner();
		boolean owns = actor.value().key().owns(owner);
		if (!owns && !(actor.value().staff() && !VehicleOwners.isUnowned(owner))) {
			return false;
		}
		return editCheck(actor.value(), vehicle, null);
	}

	private static boolean editCheck(Actor actor, ActiveVehicle vehicle, OwnershipReason reason) {
		VehicleOwnershipEditCheckEvent check =
				new VehicleOwnershipEditCheckEvent(actor.player(), vehicle, reason, actor.ref(), actor.staff());
		Bukkit.getPluginManager().callEvent(check);
		return check.isAllowed();
	}

	/** One player action, from the pre-write event to the post-write event. */
	public record Change(Actor actor, ActiveVehicle vehicle, OwnershipReason reason, CharacterRef target,
			String rawEntry, Consumer<OwnerData> mutation) {
	}

	/**
	 * Fires the pre-write event, writes, and always fires
	 * the post-write event under the same operation ID.
	 *
	 * @return the write result, or empty if a listener cancelled the change
	 */
	public static Optional<Result> perform(Change change) {
		ActiveVehicle vehicle = change.vehicle();
		Actor actor = change.actor();
		OwnerData data = vehicle.getOwnerData();
		OwnershipValues before = OwnershipStore.values(data);
		String previousOwner = data.getOwner();
		OwnerData preview = data.copy();
		change.mutation().accept(preview);
		String newOwner = preview.getOwner();
		UUID operationId = UUID.randomUUID();
		Result result = null;
		try {
			VehicleOwnershipChangeEvent pre = new VehicleOwnershipChangeEvent(actor.player(), vehicle, change.reason(),
					operationId, actor.ref(), actor.staff(), change.target(), change.rawEntry(), previousOwner, newOwner);
			Bukkit.getPluginManager().callEvent(pre);
			if (pre.isCancelled()) {
				return Optional.empty();
			}
			result = OwnershipStore.writeLive(vehicle, change.mutation());
			return Optional.of(result);
		} finally {
			Bukkit.getPluginManager().callEvent(new VehicleOwnershipChangedEvent(result == Result.OK, change.reason(),
					operationId, OwnershipStore.parseUuid(vehicle.getUUID()).orElse(null), vehicle, actor.player(),
					actor.ref(), actor.staff(), change.target(), change.rawEntry(), before,
					OwnershipStore.values(data)));
		}
	}

	/** A value or a refusal message. */
	public record Outcome<T>(T value, String refusal) {
		static <T> Outcome<T> of(T value) {
			return new Outcome<>(value, null);
		}

		static <T> Outcome<T> refused(String refusal) {
			return new Outcome<>(null, refusal);
		}
	}
}
