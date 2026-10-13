package net.tfminecraft.vehicleframework.ownership;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.Characters;
import net.tfminecraft.vehicleframework.VFLogger;
import net.tfminecraft.vehicleframework.api.OwnershipIdentity;
import net.tfminecraft.vehicleframework.api.OwnershipReason;
import net.tfminecraft.vehicleframework.api.VehicleFrameworkApi.Result;
import net.tfminecraft.vehicleframework.api.VehicleOwners;
import net.tfminecraft.vehicleframework.api.VehicleOwnershipAuthority;
import net.tfminecraft.vehicleframework.data.OwnerData;
import net.tfminecraft.vehicleframework.database.VehiclePersistence;
import net.tfminecraft.vehicleframework.events.VehicleOwnerClaimedEvent;
import net.tfminecraft.vehicleframework.events.VehicleOwnershipChangeEvent;
import net.tfminecraft.vehicleframework.events.VehicleOwnershipChangedEvent;
import net.tfminecraft.vehicleframework.events.VehicleOwnershipEditCheckEvent;
import net.tfminecraft.vehicleframework.ownership.OwnershipActions.Actor;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;

class OwnershipActionsTest {
	private final List<AutoCloseable> scopes = new ArrayList<>();
	private final PluginManager plugins = mock(PluginManager.class);
	private final ServicesManager services = mock(ServicesManager.class);
	private final VehiclePersistence persistence = mock(VehiclePersistence.class);
	private final List<Event> events = new ArrayList<>();
	private final Player alex = mock(Player.class), staff = mock(Player.class);
	private final CharacterSession kira = OwnershipFixture.session("alex", "kira", 1);
	private final CharacterSession admin = OwnershipFixture.session("staff", "warden", 2);
	private final ActiveVehicle vehicle = mock(ActiveVehicle.class);
	private final OwnerData data = new OwnerData();
	private MockedStatic<Characters> characters;

	@BeforeEach
	void setup() {
		MockedStatic<Bukkit> bukkit = keep(mockStatic(Bukkit.class));
		bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
		bukkit.when(Bukkit::getServicesManager).thenReturn(services);
		doAnswer(call -> events.add(call.getArgument(0))).when(plugins).callEvent(any(Event.class));
		characters = keep(mockStatic(Characters.class));
		keep(mockStatic(VFLogger.class));
		keep(mockStatic(VehiclePersistence.class)).when(VehiclePersistence::current).thenReturn(persistence);
		when(alex.getName()).thenReturn("Alex");
		when(staff.getName()).thenReturn("Warden");
		when(staff.hasPermission("vf.admin")).thenReturn(true);
		plays(alex, kira); plays(staff, admin);
		when(vehicle.getOwnerData()).thenReturn(data);
		when(vehicle.getUUID()).thenReturn("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
		when(persistence.saveLive(vehicle)).thenReturn(true);
		OwnershipFixture.mode(OwnershipIdentity.CHARACTERS);
	}

	@AfterEach
	void cleanup() throws Exception {
		for (int i = scopes.size() - 1; i >= 0; i--) scopes.get(i).close();
		OwnershipFixture.mode(OwnershipIdentity.NAMES);
	}

	private <T extends AutoCloseable> T keep(T scope) {
		scopes.add(scope);
		return scope;
	}

	private void plays(Player player, CharacterSession session) {
		characters.when(() -> Characters.currentSession(player)).thenReturn(Optional.ofNullable(session));
		if (session != null) characters.when(() -> Characters.isCurrent(session)).thenReturn(true);
	}

	private void ownedBy(CharacterSession session) {
		data.setOwner(VehicleOwners.character(session.ref().characterId()));
	}

	private void factionsInstalled(VehicleOwnershipAuthority authority) {
		when(plugins.getPlugin("SimpleFactions")).thenReturn(mock(Plugin.class));
		when(services.load(VehicleOwnershipAuthority.class)).thenReturn(authority);
	}

	private <T extends Event> List<T> fired(Class<T> type) {
		return events.stream().filter(type::isInstance).map(type::cast).toList();
	}

	@Test
	void actorsNeedACurrentCharacterInCharactersModeAndCarryTheStaffFlag() {
		Actor actor = OwnershipActions.actor(alex).value();
		assertEquals(kira, actor.session()); assertEquals(kira.ref(), actor.ref()); assertFalse(actor.staff());
		assertEquals("char_" + kira.ref().characterId(), actor.ownerValue());
		assertTrue(OwnershipActions.actor(staff).value().staff());
		plays(alex, null);
		assertEquals(OwnershipActions.NEED_CHARACTER, OwnershipActions.actor(alex).refusal());
		plays(staff, null);
		assertEquals(OwnershipActions.NEED_CHARACTER, OwnershipActions.actor(staff).refusal(), "staff need a character too");
		OwnershipFixture.mode(OwnershipIdentity.NAMES);
		Actor named = OwnershipActions.actor(alex).value();
		assertNull(named.session()); assertNull(named.ref()); assertEquals("player_Alex", named.ownerValue());
		OwnershipFixture.mode(OwnershipIdentity.DISABLED);
		assertEquals(OwnershipActions.DISABLED, OwnershipActions.actor(alex).refusal());
	}

	@Test
	void simpleFactionsWithoutAReadyAuthorityRefusesEveryNonStaffChange() {
		Actor player = OwnershipActions.actor(alex).value(), warden = OwnershipActions.actor(staff).value();
		assertTrue(OwnershipActions.authorityAllows(player), "no SimpleFactions installed");
		factionsInstalled(null);
		assertFalse(OwnershipActions.authorityAllows(player));
		assertTrue(OwnershipActions.authorityAllows(warden));
		VehicleOwnershipAuthority authority = mock(VehicleOwnershipAuthority.class);
		factionsInstalled(authority);
		assertFalse(OwnershipActions.authorityAllows(player), "registered but not ready");
		when(authority.ready()).thenReturn(true);
		assertTrue(OwnershipActions.authorityAllows(player));
		when(services.load(VehicleOwnershipAuthority.class)).thenReturn(null);
		assertFalse(OwnershipActions.authorityAllows(player), "looked up on every call");
		OwnershipFixture.mode(OwnershipIdentity.NAMES);
		assertTrue(OwnershipActions.authorityAllows(OwnershipActions.actor(alex).value()), "names mode has no authority gate");
	}

	@Test
	void refusalsNameTheFirstFailingCheckAndTheEditCheckRunsLast() {
		ownedBy(kira);
		assertTrue(OwnershipActions.refusal(alex, vehicle, OwnershipReason.CLEAR).isEmpty());
		VehicleOwnershipEditCheckEvent check = fired(VehicleOwnershipEditCheckEvent.class).getFirst();
		assertEquals(OwnershipReason.CLEAR, check.getReason().orElseThrow()); assertEquals(kira.ref(), check.getActor().orElseThrow());
		assertFalse(check.isStaff()); assertSame(vehicle, check.getVehicle());
		doAnswer(call -> {
			if (call.getArgument(0) instanceof VehicleOwnershipEditCheckEvent edit) edit.deny();
			return null;
		}).when(plugins).callEvent(any(VehicleOwnershipEditCheckEvent.class));
		assertEquals(OwnershipActions.NOT_ALLOWED, OwnershipActions.refusal(alex, vehicle, OwnershipReason.CLEAR).orElseThrow());
		assertFalse(OwnershipActions.allowed(alex, vehicle, OwnershipReason.CLEAR));
		factionsInstalled(null);
		assertEquals(OwnershipActions.UNAVAILABLE, OwnershipActions.refusal(alex, vehicle, OwnershipReason.CLEAR).orElseThrow());
		ownedBy(admin);
		assertEquals(OwnershipActions.NOT_OWNER, OwnershipActions.refusal(alex, vehicle, OwnershipReason.CLEAR).orElseThrow());
		plays(alex, null);
		assertEquals(OwnershipActions.NEED_CHARACTER, OwnershipActions.refusal(alex, vehicle, OwnershipReason.CLEAR).orElseThrow());
	}

	@Test
	void staffControlEveryVehicleButSeeTheMenuOnlyOnOwnedOnes() {
		ownedBy(kira);
		Actor warden = OwnershipActions.actor(staff).value();
		assertTrue(OwnershipActions.controls(warden, vehicle));
		assertTrue(OwnershipActions.mayOpenMenu(staff, vehicle));
		assertTrue(OwnershipActions.mayOpenMenu(alex, vehicle));
		assertTrue(fired(VehicleOwnershipEditCheckEvent.class).getFirst().isMenu());
		data.setOwner("none");
		assertFalse(OwnershipActions.mayOpenMenu(staff, vehicle));
		assertFalse(OwnershipActions.mayOpenMenu(alex, vehicle));
		ownedBy(admin);
		assertFalse(OwnershipActions.mayOpenMenu(alex, vehicle));
		plays(alex, null);
		ownedBy(kira);
		assertFalse(OwnershipActions.mayOpenMenu(alex, vehicle));
		plays(alex, kira);
		doAnswer(call -> {
			if (call.getArgument(0) instanceof VehicleOwnershipEditCheckEvent edit) edit.deny();
			return null;
		}).when(plugins).callEvent(any(VehicleOwnershipEditCheckEvent.class));
		assertFalse(OwnershipActions.mayOpenMenu(alex, vehicle));
	}

	private Optional<Result> claim(Consumer<OwnerData> mutation) {
		Actor actor = OwnershipActions.actor(alex).value();
		return OwnershipActions.perform(new OwnershipActions.Change(actor, vehicle, OwnershipReason.CLAIM, actor.ref(), null, mutation));
	}

	@Test
	void aWrittenClaimFiresPreLegacyAndPostEventsUnderOneOperation() {
		String owner = "char_" + kira.ref().characterId();
		assertEquals(Optional.of(Result.OK), claim(d -> d.setOwner(owner)));
		assertEquals(owner, data.getOwner());
		assertEquals(3, events.size());
		VehicleOwnershipChangeEvent pre = (VehicleOwnershipChangeEvent) events.get(0);
		VehicleOwnerClaimedEvent legacy = (VehicleOwnerClaimedEvent) events.get(1);
		VehicleOwnershipChangedEvent post = (VehicleOwnershipChangedEvent) events.get(2);
		assertEquals("none", pre.getPreviousOwner()); assertEquals(owner, pre.getNewOwner());
		assertEquals(kira.ref(), pre.getTarget().orElseThrow());
		assertEquals(owner, legacy.getNewOwner()); assertSame(alex, legacy.getPlayer());
		assertTrue(post.isSuccess()); assertEquals(pre.getOperationId(), post.getOperationId());
		assertEquals("none", post.getPrevious().owner()); assertEquals(owner, post.getCurrent().owner());
		assertEquals(java.util.UUID.fromString(vehicle.getUUID()), post.getVehicleId());
	}

	@Test
	void aCancelledChangeStillReportsFailureUnderItsOperation() {
		doAnswer(call -> {
			if (call.getArgument(0) instanceof VehicleOwnershipChangeEvent pre) pre.setCancelled(true);
			return events.add(call.getArgument(0));
		}).when(plugins).callEvent(any(Event.class));
		assertTrue(claim(d -> d.setOwner("char_x")).isEmpty());
		assertEquals("none", data.getOwner());
		assertTrue(fired(VehicleOwnerClaimedEvent.class).isEmpty());
		VehicleOwnershipChangedEvent post = fired(VehicleOwnershipChangedEvent.class).getFirst();
		assertFalse(post.isSuccess());
		assertEquals(fired(VehicleOwnershipChangeEvent.class).getFirst().getOperationId(), post.getOperationId());
		verify(persistence, never()).saveLive(any(ActiveVehicle.class));
	}

	@Test
	void theLegacyClaimEventCanStillVetoAClaim() {
		doAnswer(call -> {
			if (call.getArgument(0) instanceof VehicleOwnerClaimedEvent legacy) legacy.setCancelled(true);
			return events.add(call.getArgument(0));
		}).when(plugins).callEvent(any(Event.class));
		assertTrue(claim(d -> d.setOwner("char_x")).isEmpty());
		assertEquals("none", data.getOwner());
		assertFalse(fired(VehicleOwnershipChangedEvent.class).getFirst().isSuccess());
	}

	@Test
	void aFailedSaveRestoresTheVehicleAndReportsFailure() {
		data.setWhiteList(new ArrayList<>(List.of("char_old")));
		when(persistence.saveLive(vehicle)).thenReturn(false);
		assertEquals(Optional.of(Result.SAVE_FAILED), claim(d -> { d.setOwner("char_x"); d.getWhiteList().clear(); }));
		assertEquals("none", data.getOwner()); assertEquals(List.of("char_old"), data.getWhiteList());
		VehicleOwnershipChangedEvent post = fired(VehicleOwnershipChangedEvent.class).getFirst();
		assertFalse(post.isSuccess()); assertEquals("none", post.getCurrent().owner());
	}

	@Test
	void anEventSystemFailureStillFiresThePostWriteEvent() {
		doAnswer(call -> {
			if (call.getArgument(0) instanceof VehicleOwnershipChangeEvent) throw new IllegalStateException("listener exploded");
			return events.add(call.getArgument(0));
		}).when(plugins).callEvent(any(Event.class));
		assertThrows(IllegalStateException.class, () -> claim(d -> d.setOwner("char_x")));
		assertFalse(fired(VehicleOwnershipChangedEvent.class).getFirst().isSuccess());
		assertEquals("none", data.getOwner());
	}

	@Test
	void nonUuidVehicleIdsAreReportedWithoutAnId() {
		when(vehicle.getUUID()).thenReturn("legacy-id");
		claim(d -> d.setOwner("char_x"));
		assertNull(fired(VehicleOwnershipChangedEvent.class).getFirst().getVehicleId());
	}
}
