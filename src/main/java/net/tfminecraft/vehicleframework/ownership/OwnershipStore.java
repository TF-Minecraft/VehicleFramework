package net.tfminecraft.vehicleframework.ownership;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import net.tfminecraft.vehicleframework.VFLogger;
import net.tfminecraft.vehicleframework.VehicleFramework;
import net.tfminecraft.vehicleframework.api.OwnerChange;
import net.tfminecraft.vehicleframework.api.OwnershipIdentity;
import net.tfminecraft.vehicleframework.api.OwnershipMeta;
import net.tfminecraft.vehicleframework.api.OwnershipValues;
import net.tfminecraft.vehicleframework.api.SystemReason;
import net.tfminecraft.vehicleframework.api.VehicleFrameworkApi.Result;
import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.data.OwnerData;
import net.tfminecraft.vehicleframework.database.VehiclePersistence;
import net.tfminecraft.vehicleframework.database.VehicleRepository;
import net.tfminecraft.vehicleframework.database.VehicleSnapshot;
import net.tfminecraft.vehicleframework.events.VehicleOwnershipChangedEvent;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;

/** Reads and writes ownership fields for loaded vehicles and stored rows. */
public final class OwnershipStore {

	private OwnershipStore() {
	}

	public static OwnershipValues values(OwnerData data) {
		return new OwnershipValues(data.getOwner(), data.getWhiteList(), data.isWhiteListed(),
				data.isTicketsEnabled(), data.getTicketId());
	}

	/**
	 * Applies the mutation to a loaded vehicle and saves it. A failed save, or a mutation that
	 * throws, puts every field back.
	 */
	public static Result writeLive(ActiveVehicle vehicle, Consumer<OwnerData> mutation) {
		OwnerData data = vehicle.getOwnerData();
		OwnerData before = data.copy();
		boolean saved;
		try {
			mutation.accept(data);
			VehiclePersistence persistence = VehiclePersistence.current();
			saved = persistence != null && persistence.saveLive(vehicle);
		} catch (RuntimeException ex) {
			VFLogger.log("Ownership write failed for " + vehicle.getUUID() + ": " + ex);
			saved = false;
		}
		if (!saved) {
			data.restore(before);
			VFLogger.log("Could not save the ownership change of " + vehicle.getUUID() + "; nothing changed");
			return Result.SAVE_FAILED;
		}
		return Result.OK;
	}

	public static Result setOwnership(UUID vehicleId, OwnerChange change, SystemReason reason) {
		Objects.requireNonNull(vehicleId, "vehicleId");
		Objects.requireNonNull(change, "change");
		Objects.requireNonNull(reason, "reason");
		requireMainThread();
		if (change.whitelist() != null && reason != SystemReason.MIGRATE) {
			throw new IllegalArgumentException("Whitelist entries are only accepted with MIGRATE");
		}
		if (OwnershipMode.identity() != OwnershipIdentity.CHARACTERS) {
			return Result.REFUSED_MODE;
		}
		Consumer<OwnerData> mutation = data -> applySystem(data, change, reason);
		ActiveVehicle live = VehicleFramework.getVehicleManager().getByUUID(vehicleId.toString());
		if (live != null) {
			if (live.isDestroyed()) {
				return Result.NOT_FOUND;
			}
			OwnershipValues before = values(live.getOwnerData());
			Result result = writeLive(live, mutation);
			if (result == Result.OK) {
				fireWritten(reason, vehicleId, live, before, values(live.getOwnerData()));
			}
			return result;
		}
		StoredWrite stored = writeStored(vehicleId.toString(), mutation);
		if (stored.result() == Result.OK) {
			fireWritten(reason, vehicleId, null, stored.before(), stored.after());
		}
		return stored.result();
	}

	static void applySystem(OwnerData data, OwnerChange change, SystemReason reason) {
		data.setOwner(change.owner());
		switch (reason) {
			case MIGRATE -> {
				if (change.whitelist() != null) {
					data.setWhiteList(new ArrayList<>(change.whitelist()));
				}
			}
			case CONSTRUCT -> {
				data.setWhiteList(new ArrayList<>());
				data.setWhiteListed(Cache.allowWhitelist && Cache.whitelistedByDefault);
			}
			case RELEASE, HANDOVER -> {
				data.setWhiteList(new ArrayList<>());
				data.setWhiteListed(false);
			}
			case TRANSFER -> data.setWhiteList(new ArrayList<>());
			case RESTAMP -> {
			}
		}
	}

	private static void fireWritten(SystemReason reason, UUID vehicleId, ActiveVehicle live, OwnershipValues before,
			OwnershipValues after) {
		Bukkit.getPluginManager().callEvent(new VehicleOwnershipChangedEvent(true, reason.reason(),
				UUID.randomUUID(), vehicleId, live, null, null, false, null, null, before, after));
	}

	record StoredWrite(Result result, OwnershipValues before, OwnershipValues after) {
	}

	/** Rewrites the owner column and the payload's owner fields of a stored row in one transaction. */
	@SuppressWarnings("unchecked")
	static StoredWrite writeStored(String uuid, Consumer<OwnerData> mutation) {
		VehicleRepository repository = VehicleFramework.getVehicleRepository();
		if (repository == null) {
			VFLogger.log("Cannot change the owner of " + uuid + ": SQLite is not open");
			return new StoredWrite(Result.SAVE_FAILED, null, null);
		}
		StoredWrite[] outcome = { new StoredWrite(Result.NOT_FOUND, null, null) };
		try {
			repository.runInTransaction(() -> {
				Optional<VehicleSnapshot> existing = repository.findLive(uuid);
				if (existing.isEmpty()) {
					return;
				}
				VehicleSnapshot row = existing.get();
				JSONObject json = parse(row.getPayloadJson());
				OwnerData data = fromPayload(json);
				OwnershipValues before = values(data);
				mutation.accept(data);
				json.put("owner", data.getOwner());
				json.put("whitelisted", data.isWhiteListed());
				JSONArray entries = new JSONArray();
				entries.addAll(data.getWhiteList());
				json.put("whitelist", entries);
				VehicleSnapshot next = row.withPayload(json.toJSONString(), row.getName(), data.getOwner(),
						System.currentTimeMillis());
				if (!repository.saveLive(next)) {
					throw new IllegalStateException("SQL wrote 0 rows");
				}
				outcome[0] = new StoredWrite(Result.OK, before, values(data));
			});
		} catch (RuntimeException ex) {
			VFLogger.log("Could not change the stored owner of " + uuid + ": " + ex);
			return new StoredWrite(Result.SAVE_FAILED, null, null);
		}
		return outcome[0];
	}

	public static List<OwnershipMeta> listOwnershipMeta() {
		requireMainThread();
		VehicleRepository repository = openRepository();
		Map<String, MetaBuilder> rows = new LinkedHashMap<>();
		for (VehicleSnapshot row : repository.listAllLive()) {
			builder(rows, row.getUuid()).ifPresent(builder -> builder.stored(row));
		}
		for (ActiveVehicle vehicle : VehicleFramework.getVehicleManager().get().values()) {
			if (!vehicle.isDestroyed()) {
				builder(rows, vehicle.getUUID()).ifPresent(builder -> builder.live(vehicle));
			}
		}
		List<OwnershipMeta> result = new ArrayList<>();
		for (MetaBuilder builder : rows.values()) {
			result.add(builder.build());
		}
		return result;
	}

	public static Optional<OwnershipMeta> readOwnershipMeta(UUID vehicleId) {
		Objects.requireNonNull(vehicleId, "vehicleId");
		requireMainThread();
		VehicleRepository repository = openRepository();
		MetaBuilder builder = new MetaBuilder(vehicleId);
		repository.findLive(vehicleId.toString()).ifPresent(builder::stored);
		ActiveVehicle live = VehicleFramework.getVehicleManager().getByUUID(vehicleId.toString());
		if (live != null && !live.isDestroyed()) {
			builder.live(live);
		}
		return builder.empty() ? Optional.empty() : Optional.of(builder.build());
	}

	private static VehicleRepository openRepository() {
		VehicleRepository repository = VehicleFramework.getVehicleRepository();
		if (repository == null) {
			throw new IllegalStateException("VehicleFramework SQLite is not open");
		}
		return repository;
	}

	private static Optional<MetaBuilder> builder(Map<String, MetaBuilder> rows, String uuid) {
		Optional<UUID> id = parseUuid(uuid);
		if (id.isEmpty()) {
			VFLogger.log("Skipping vehicle with an unreadable ID in the ownership list: " + uuid);
			return Optional.empty();
		}
		return Optional.of(rows.computeIfAbsent(id.get().toString(), key -> new MetaBuilder(id.get())));
	}

	static Optional<UUID> parseUuid(String uuid) {
		try {
			return uuid == null ? Optional.empty() : Optional.of(UUID.fromString(uuid.toLowerCase(Locale.ROOT)));
		} catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

	private static JSONObject parse(String payload) {
		try {
			Object parsed = new JSONParser().parse(payload);
			if (parsed instanceof JSONObject json) {
				return json;
			}
			throw new IllegalStateException("payload is not a JSON object");
		} catch (ParseException ex) {
			throw new IllegalStateException("payload is not valid JSON", ex);
		}
	}

	static OwnerData fromPayload(JSONObject json) {
		OwnerData data = new OwnerData();
		Object owner = json.get("owner");
		data.setOwner(owner == null ? "none" : String.valueOf(owner));
		data.setWhiteListed(Boolean.TRUE.equals(json.get("whitelisted")));
		List<String> entries = new ArrayList<>();
		if (json.get("whitelist") instanceof JSONArray array) {
			for (Object entry : array) {
				if (entry != null) {
					entries.add(String.valueOf(entry));
				}
			}
		}
		data.setWhiteList(entries);
		Object ticketId = json.get("ticketId");
		data.setTicketId(ticketId == null ? null : String.valueOf(ticketId));
		if (Boolean.TRUE.equals(json.get("ticketsEnabled"))) {
			data.setTicketsEnabled(true);
		}
		return data;
	}

	private static void requireMainThread() {
		if (!Bukkit.isPrimaryThread()) {
			throw new IllegalStateException("Vehicle ownership must be used on the main thread");
		}
	}

	private static final class MetaBuilder {
		private final UUID vehicleId;
		private String typeId;
		private String name;
		private String storedOwnerColumn;
		private Optional<OwnershipValues> stored = Optional.empty();
		private Optional<OwnershipValues> live = Optional.empty();

		MetaBuilder(UUID vehicleId) {
			this.vehicleId = vehicleId;
		}

		void stored(VehicleSnapshot row) {
			typeId = row.getTypeId();
			name = row.getName();
			storedOwnerColumn = row.getOwner();
			try {
				stored = Optional.of(values(fromPayload(parse(row.getPayloadJson()))));
			} catch (RuntimeException ex) {
				VFLogger.log("Unreadable payload for vehicle " + row.getUuid() + ": " + ex.getMessage());
			}
		}

		void live(ActiveVehicle vehicle) {
			typeId = vehicle.getId();
			name = vehicle.getName();
			live = Optional.of(values(vehicle.getOwnerData()));
		}

		boolean empty() {
			return storedOwnerColumn == null && live.isEmpty();
		}

		OwnershipMeta build() {
			return new OwnershipMeta(vehicleId, typeId, name, storedOwnerColumn, stored, live);
		}
	}
}
