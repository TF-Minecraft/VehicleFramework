package net.tfminecraft.vehicleframework.ownership;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.event.Event;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.tfminecraft.vehicleframework.VFLogger;
import net.tfminecraft.vehicleframework.VehicleFramework;
import net.tfminecraft.vehicleframework.api.OwnerChange;
import net.tfminecraft.vehicleframework.api.OwnershipIdentity;
import net.tfminecraft.vehicleframework.api.OwnershipMeta;
import net.tfminecraft.vehicleframework.api.OwnershipReason;
import net.tfminecraft.vehicleframework.api.OwnershipValues;
import net.tfminecraft.vehicleframework.api.SystemReason;
import net.tfminecraft.vehicleframework.api.VehicleFrameworkApi;
import net.tfminecraft.vehicleframework.api.VehicleFrameworkApi.Result;
import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.data.OwnerData;
import net.tfminecraft.vehicleframework.database.VehiclePersistence;
import net.tfminecraft.vehicleframework.database.VehicleRepository;
import net.tfminecraft.vehicleframework.database.VehicleSnapshot;
import net.tfminecraft.vehicleframework.events.VehicleOwnershipChangedEvent;
import net.tfminecraft.vehicleframework.managers.VehicleManager;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;

class OwnershipStoreTest {
	private static final UUID STORED = UUID.fromString("11111111-2222-4333-8444-555555555555");
	private static final UUID LOADED = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
	private static final UUID KIRA = UUID.fromString("0b1f2e9c-6a3d-4c55-8a7e-2f1d9c3b4a51");
	private static final String PAYLOAD = "{\"id\":\"cart\",\"owner\":\"player_Alice\",\"whitelisted\":true,"
			+ "\"whitelist\":[\"player_Bob\"],\"ticketsEnabled\":true,\"ticketId\":\"t-1\",\"components\":{\"hull\":{\"damage\":5}}}";

	@TempDir Path temp;
	private final List<AutoCloseable> scopes = new ArrayList<>();
	private final PluginManager plugins = mock(PluginManager.class);
	private final VehicleManager manager = mock(VehicleManager.class);
	private final VehiclePersistence persistence = mock(VehiclePersistence.class);
	private final List<Event> events = new ArrayList<>();
	private final Map<Entity, ActiveVehicle> loaded = new HashMap<>();
	private MockedStatic<Bukkit> bukkit;
	private MockedStatic<VehicleFramework> framework;
	private MockedStatic<VehiclePersistence> persistenceAccess;
	private VehicleRepository repository;
	private boolean allowWhitelist, whitelistedByDefault;

	@BeforeEach
	void setup() {
		bukkit = keep(mockStatic(Bukkit.class));
		bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
		bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
		doAnswer(call -> events.add(call.getArgument(0))).when(plugins).callEvent(any(Event.class));
		keep(mockStatic(VFLogger.class));
		repository = VehicleRepository.open(temp.resolve("vehicles.db").toFile());
		framework = keep(mockStatic(VehicleFramework.class));
		framework.when(VehicleFramework::getVehicleManager).thenReturn(manager);
		framework.when(VehicleFramework::getVehicleRepository).thenReturn(repository);
		persistenceAccess = keep(mockStatic(VehiclePersistence.class));
		persistenceAccess.when(VehiclePersistence::current).thenReturn(persistence);
		when(manager.get()).thenReturn(new HashMap<>(loaded));
		allowWhitelist = Cache.allowWhitelist; whitelistedByDefault = Cache.whitelistedByDefault;
		OwnershipFixture.mode(OwnershipIdentity.CHARACTERS);
	}

	@AfterEach
	void cleanup() throws Exception {
		repository.close();
		for (int i = scopes.size() - 1; i >= 0; i--) scopes.get(i).close();
		Cache.allowWhitelist = allowWhitelist; Cache.whitelistedByDefault = whitelistedByDefault;
		OwnershipFixture.mode(OwnershipIdentity.NAMES);
	}

	private <T extends AutoCloseable> T keep(T scope) {
		scopes.add(scope);
		return scope;
	}

	private void store(UUID id, String payload) {
		repository.saveLive(new VehicleSnapshot(id.toString(), "cart", "Stored cart", "player_Alice", "world", 0, 64, 0, 0, 0, 0,
				payload, 2, 1, false, 1));
	}

	private ActiveVehicle live(UUID id, String owner) {
		ActiveVehicle vehicle = mock(ActiveVehicle.class);
		OwnerData data = new OwnerData();
		data.setOwner(owner); data.setWhiteListed(true); data.setWhiteList(new ArrayList<>(List.of("player_Bob")));
		data.setTicketsEnabled(true);
		when(vehicle.getOwnerData()).thenReturn(data);
		when(vehicle.getUUID()).thenReturn(id.toString());
		when(vehicle.getId()).thenReturn("boat");
		when(vehicle.getName()).thenReturn("Live boat");
		when(manager.getByUUID(id.toString())).thenReturn(vehicle);
		loaded.put(mock(Entity.class), vehicle);
		when(manager.get()).thenReturn(new HashMap<>(loaded));
		return vehicle;
	}

	private JsonObject payload(UUID id) {
		return JsonParser.parseString(repository.findLive(id.toString()).orElseThrow().getPayloadJson()).getAsJsonObject();
	}

	private List<VehicleOwnershipChangedEvent> written() {
		return events.stream().filter(VehicleOwnershipChangedEvent.class::isInstance).map(VehicleOwnershipChangedEvent.class::cast).toList();
	}

	@Test
	void outsideCharactersModeEveryWriteIsRefusedBeforeAnythingChanges() {
		store(STORED, PAYLOAD);
		for (OwnershipIdentity identity : List.of(OwnershipIdentity.NAMES, OwnershipIdentity.DISABLED)) {
			OwnershipFixture.mode(identity);
			assertEquals(Result.REFUSED_MODE, VehicleFrameworkApi.setOwnership(STORED, OwnerChange.character(KIRA), SystemReason.RESTAMP));
		}
		assertEquals("player_Alice", repository.findLive(STORED.toString()).orElseThrow().getOwner());
		assertTrue(events.isEmpty());
	}

	@Test
	void writesAreMainThreadOnlyAndOnlyMigrateMaySeedTheWhitelist() {
		assertThrows(IllegalArgumentException.class,
				() -> VehicleFrameworkApi.setOwnership(STORED, OwnerChange.seeded("none", List.of()), SystemReason.RESTAMP));
		assertThrows(NullPointerException.class, () -> VehicleFrameworkApi.setOwnership(null, OwnerChange.unowned(), SystemReason.RESTAMP));
		bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
		assertThrows(IllegalStateException.class, () -> VehicleFrameworkApi.setOwnership(STORED, OwnerChange.unowned(), SystemReason.RESTAMP));
		assertThrows(IllegalStateException.class, VehicleFrameworkApi::listOwnershipMeta);
		assertThrows(IllegalStateException.class, () -> VehicleFrameworkApi.readOwnershipMeta(STORED));
	}

	@Test
	void aLoadedVehicleIsAuthoritativeAndItsStoredRowIsNotTouched() {
		store(LOADED, PAYLOAD);
		ActiveVehicle vehicle = live(LOADED, "player_Alice");
		when(persistence.saveLive(vehicle)).thenReturn(true);
		assertEquals(Result.OK, VehicleFrameworkApi.setOwnership(LOADED, OwnerChange.character(KIRA), SystemReason.RESTAMP));
		assertEquals("char_" + KIRA, vehicle.getOwnerData().getOwner());
		assertEquals(List.of("player_Bob"), vehicle.getOwnerData().getWhiteList());
		assertEquals("player_Alice", repository.findLive(LOADED.toString()).orElseThrow().getOwner(), "the live save owns the row");
		VehicleOwnershipChangedEvent event = written().getFirst();
		assertTrue(event.isSuccess()); assertEquals(OwnershipReason.RESTAMP, event.getReason());
		assertEquals(LOADED, event.getVehicleId()); assertSame(vehicle, event.getVehicle().orElseThrow());
		assertEquals("player_Alice", event.getPrevious().owner()); assertEquals("char_" + KIRA, event.getCurrent().owner());
		assertTrue(event.getPlayer().isEmpty()); assertNotNull(event.getOperationId());
	}

	@Test
	void aFailedLiveSaveRestoresEveryFieldAndFiresNoEvent() {
		ActiveVehicle vehicle = live(LOADED, "player_Alice");
		OwnerData before = vehicle.getOwnerData().copy();
		when(persistence.saveLive(vehicle)).thenReturn(false);
		assertEquals(Result.SAVE_FAILED, VehicleFrameworkApi.setOwnership(LOADED, OwnerChange.character(KIRA), SystemReason.HANDOVER));
		assertEquals(OwnershipStore.values(before), OwnershipStore.values(vehicle.getOwnerData()));
		when(persistence.saveLive(vehicle)).thenThrow(new IllegalStateException("disk full"));
		assertEquals(Result.SAVE_FAILED, VehicleFrameworkApi.setOwnership(LOADED, OwnerChange.character(KIRA), SystemReason.HANDOVER));
		assertEquals(OwnershipStore.values(before), OwnershipStore.values(vehicle.getOwnerData()));
		persistenceAccess.when(VehiclePersistence::current).thenReturn(null);
		assertEquals(Result.SAVE_FAILED, VehicleFrameworkApi.setOwnership(LOADED, OwnerChange.character(KIRA), SystemReason.HANDOVER));
		assertEquals(OwnershipStore.values(before), OwnershipStore.values(vehicle.getOwnerData()));
		assertTrue(written().isEmpty());
	}

	@Test
	void aDestroyedLoadedVehicleIsNotFoundAndHasNoStoredFallback() {
		store(LOADED, PAYLOAD);
		ActiveVehicle vehicle = live(LOADED, "player_Alice");
		when(vehicle.isDestroyed()).thenReturn(true);
		assertEquals(Result.NOT_FOUND, VehicleFrameworkApi.setOwnership(LOADED, OwnerChange.character(KIRA), SystemReason.RESTAMP));
		assertEquals("player_Alice", repository.findLive(LOADED.toString()).orElseThrow().getOwner());
		verify(persistence, never()).saveLive(any(ActiveVehicle.class));
	}

	@ParameterizedTest
	@EnumSource(SystemReason.class)
	void eachReasonDisposesOfTheWhitelistAsDesigned(SystemReason reason) {
		Cache.allowWhitelist = true; Cache.whitelistedByDefault = true;
		store(STORED, PAYLOAD);
		OwnerChange change = reason == SystemReason.MIGRATE
				? OwnerChange.seeded("char_" + KIRA, List.of("char_seed"))
				: OwnerChange.character(KIRA);
		assertEquals(Result.OK, VehicleFrameworkApi.setOwnership(STORED, change, reason));
		JsonObject json = payload(STORED);
		List<String> entries = new ArrayList<>();
		json.getAsJsonArray("whitelist").forEach(entry -> entries.add(entry.getAsString()));
		List<String> expectedEntries = switch (reason) {
			case RESTAMP -> List.of("player_Bob");
			case MIGRATE -> List.of("char_seed");
			default -> List.of();
		};
		boolean expectedFlag = switch (reason) {
			case RELEASE, HANDOVER -> false;
			default -> true;
		};
		assertEquals(expectedEntries, entries);
		assertEquals(expectedFlag, json.get("whitelisted").getAsBoolean());
		assertEquals("char_" + KIRA, json.get("owner").getAsString());
		assertTrue(json.get("ticketsEnabled").getAsBoolean(), "tickets never change");
		assertEquals("t-1", json.get("ticketId").getAsString());
		assertEquals(5, json.getAsJsonObject("components").getAsJsonObject("hull").get("damage").getAsInt());
		assertEquals("char_" + KIRA, repository.findLive(STORED.toString()).orElseThrow().getOwner());
		VehicleOwnershipChangedEvent event = written().getFirst();
		assertEquals(reason.reason(), event.getReason()); assertTrue(event.getVehicle().isEmpty());
		assertEquals("player_Alice", event.getPrevious().owner()); assertEquals(entries, event.getCurrent().whitelist());
	}

	@Test
	void constructTurnsTheFlagOffWhenWhitelistsAreNotOnByDefault() {
		Cache.allowWhitelist = true; Cache.whitelistedByDefault = false;
		ActiveVehicle vehicle = live(LOADED, "none");
		when(persistence.saveLive(vehicle)).thenReturn(true);
		assertEquals(Result.OK, VehicleFrameworkApi.setOwnership(LOADED, OwnerChange.character(KIRA), SystemReason.CONSTRUCT));
		assertFalse(vehicle.getOwnerData().isWhiteListed());
		assertTrue(vehicle.getOwnerData().getWhiteList().isEmpty());
		assertTrue(vehicle.getOwnerData().isTicketsEnabled());
	}

	@Test
	void migrateWithoutSeedKeepsTheEntries() {
		store(STORED, PAYLOAD);
		assertEquals(Result.OK, VehicleFrameworkApi.setOwnership(STORED, OwnerChange.noCharacter(), SystemReason.MIGRATE));
		assertEquals("player_Bob", payload(STORED).getAsJsonArray("whitelist").get(0).getAsString());
		assertEquals("char_none", repository.findLive(STORED.toString()).orElseThrow().getOwner());
	}

	@Test
	void missingTombstonedAndUnreadableRowsAreNotFoundOrLeftUntouched() {
		assertEquals(Result.NOT_FOUND, VehicleFrameworkApi.setOwnership(STORED, OwnerChange.unowned(), SystemReason.RELEASE));
		store(STORED, PAYLOAD);
		repository.tombstone(STORED.toString());
		assertEquals(Result.NOT_FOUND, VehicleFrameworkApi.setOwnership(STORED, OwnerChange.unowned(), SystemReason.RELEASE));
		store(STORED, "[1]");
		assertEquals(Result.SAVE_FAILED, VehicleFrameworkApi.setOwnership(STORED, OwnerChange.unowned(), SystemReason.RELEASE));
		store(STORED, "{");
		assertEquals(Result.SAVE_FAILED, VehicleFrameworkApi.setOwnership(STORED, OwnerChange.unowned(), SystemReason.RELEASE));
		assertEquals("{", repository.findLive(STORED.toString()).orElseThrow().getPayloadJson());
		assertEquals("player_Alice", repository.findLive(STORED.toString()).orElseThrow().getOwner());
		assertTrue(written().isEmpty());
	}

	@Test
	void aStoredWriteThatSavesNothingRollsBackAndAClosedDatabaseFails() {
		VehicleRepository stuck = mock(VehicleRepository.class);
		VehicleSnapshot row = new VehicleSnapshot(STORED.toString(), "cart", "Cart", "player_Alice", "world", 0, 64, 0, 0, 0, 0,
				PAYLOAD, 2, 1, false, 1);
		when(stuck.findLive(STORED.toString())).thenReturn(Optional.of(row));
		doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; }).when(stuck).runInTransaction(any());
		framework.when(VehicleFramework::getVehicleRepository).thenReturn(stuck);
		assertEquals(Result.SAVE_FAILED, VehicleFrameworkApi.setOwnership(STORED, OwnerChange.unowned(), SystemReason.RELEASE));
		framework.when(VehicleFramework::getVehicleRepository).thenReturn(null);
		assertEquals(Result.SAVE_FAILED, VehicleFrameworkApi.setOwnership(STORED, OwnerChange.unowned(), SystemReason.RELEASE));
		assertThrows(IllegalStateException.class, VehicleFrameworkApi::listOwnershipMeta);
		assertThrows(IllegalStateException.class, () -> VehicleFrameworkApi.readOwnershipMeta(STORED));
	}

	@Test
	void listingKeepsStoredAndLiveValuesApartAndSkipsTombstones() {
		store(STORED, PAYLOAD);
		store(LOADED, PAYLOAD);
		UUID gone = UUID.randomUUID(), unreadable = UUID.randomUUID(), fresh = UUID.randomUUID();
		store(gone, PAYLOAD); repository.tombstone(gone.toString());
		store(unreadable, "{");
		repository.saveLive(new VehicleSnapshot("not-a-uuid", "cart", "x", "none", "world", 0, 64, 0, 0, 0, 0, PAYLOAD, 2, 1, false, 1));
		ActiveVehicle vehicle = live(LOADED, "char_" + KIRA);
		live(fresh, "none");
		ActiveVehicle destroyed = live(UUID.randomUUID(), "none");
		when(destroyed.isDestroyed()).thenReturn(true);
		Map<UUID, OwnershipMeta> rows = new HashMap<>();
		for (OwnershipMeta meta : VehicleFrameworkApi.listOwnershipMeta()) rows.put(meta.vehicleId(), meta);
		assertEquals(4, rows.size());
		OwnershipMeta stored = rows.get(STORED);
		assertFalse(stored.isLive()); assertEquals("player_Alice", stored.storedOwnerColumn());
		assertEquals(new OwnershipValues("player_Alice", List.of("player_Bob"), true, true, "t-1"), stored.stored().orElseThrow());
		OwnershipMeta both = rows.get(LOADED);
		assertEquals("player_Alice", both.stored().orElseThrow().owner());
		assertEquals("char_" + KIRA, both.live().orElseThrow().owner());
		assertEquals("boat", both.typeId()); assertEquals(vehicle.getOwnerData().getOwner(), both.current().orElseThrow().owner());
		assertTrue(rows.get(unreadable).hasStoredRow()); assertTrue(rows.get(unreadable).stored().isEmpty());
		assertFalse(rows.get(fresh).hasStoredRow()); assertTrue(rows.get(fresh).isLive());
		assertFalse(rows.containsKey(gone));
	}

	@Test
	void readingOneVehicleCombinesItsRowAndLiveObject() {
		assertTrue(VehicleFrameworkApi.readOwnershipMeta(STORED).isEmpty());
		store(STORED, PAYLOAD);
		OwnershipMeta stored = VehicleFrameworkApi.readOwnershipMeta(STORED).orElseThrow();
		assertTrue(stored.hasStoredRow()); assertFalse(stored.isLive()); assertEquals("cart", stored.typeId());
		live(LOADED, "none");
		OwnershipMeta live = VehicleFrameworkApi.readOwnershipMeta(LOADED).orElseThrow();
		assertFalse(live.hasStoredRow()); assertEquals("none", live.live().orElseThrow().owner());
		ActiveVehicle destroyed = live(STORED, "none");
		when(destroyed.isDestroyed()).thenReturn(true);
		assertFalse(VehicleFrameworkApi.readOwnershipMeta(STORED).orElseThrow().isLive());
	}

	@Test
	void payloadsWithMissingFieldsReadAsUnownedAndUnlisted() {
		store(STORED, "{\"id\":\"cart\",\"whitelist\":[\"a\",null],\"whitelisted\":\"yes\"}");
		OwnershipValues values = VehicleFrameworkApi.readOwnershipMeta(STORED).orElseThrow().stored().orElseThrow();
		assertEquals(new OwnershipValues("none", List.of("a"), false, false, null), values);
	}
}
