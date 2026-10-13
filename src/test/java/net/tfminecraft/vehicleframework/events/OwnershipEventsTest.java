package net.tfminecraft.vehicleframework.events;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.vehicleframework.api.OwnershipReason;
import net.tfminecraft.vehicleframework.api.OwnershipValues;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;

class OwnershipEventsTest {
	private final Player player = mock(Player.class);
	private final ActiveVehicle vehicle = mock(ActiveVehicle.class);
	private final CharacterRef actor = new CharacterRef(UUID.randomUUID(), UUID.randomUUID());
	private final CharacterRef target = new CharacterRef(UUID.randomUUID(), UUID.randomUUID());

	@Test
	void preWriteEventCarriesTheActionAndCanBeCancelled() {
		UUID operation = UUID.randomUUID();
		VehicleOwnershipChangeEvent event = new VehicleOwnershipChangeEvent(player, vehicle, OwnershipReason.WHITELIST_ADD,
				operation, actor, true, target, "char_x", "char_a", "char_a");
		assertSame(player, event.getPlayer()); assertSame(vehicle, event.getVehicle());
		assertEquals(OwnershipReason.WHITELIST_ADD, event.getReason()); assertEquals(operation, event.getOperationId());
		assertEquals(actor, event.getActor().orElseThrow()); assertTrue(event.isStaff());
		assertEquals(target, event.getTarget().orElseThrow()); assertEquals("char_x", event.getRawEntry().orElseThrow());
		assertEquals("char_a", event.getPreviousOwner()); assertEquals("char_a", event.getNewOwner());
		assertFalse(event.isCancelled()); event.setCancelled(true); assertTrue(event.isCancelled());
		assertSame(VehicleOwnershipChangeEvent.getHandlerList(), event.getHandlers());
		VehicleOwnershipChangeEvent names = new VehicleOwnershipChangeEvent(player, vehicle, OwnershipReason.CLAIM,
				operation, null, false, null, null, "none", "player_Alex");
		assertTrue(names.getActor().isEmpty()); assertTrue(names.getTarget().isEmpty()); assertTrue(names.getRawEntry().isEmpty());
		assertThrows(NullPointerException.class, () -> new VehicleOwnershipChangeEvent(null, vehicle, OwnershipReason.CLAIM,
				operation, null, false, null, null, "none", "none"));
	}

	@Test
	void postWriteEventReportsOutcomeAndBothValueSets() {
		OwnershipValues before = new OwnershipValues("none", List.of(), false, false, null);
		OwnershipValues after = new OwnershipValues("char_a", List.of(), false, false, null);
		UUID operation = UUID.randomUUID(), id = UUID.randomUUID();
		VehicleOwnershipChangedEvent event = new VehicleOwnershipChangedEvent(true, OwnershipReason.CLAIM, operation, id,
				vehicle, player, actor, false, target, "entry", before, after);
		assertTrue(event.isSuccess()); assertEquals(OwnershipReason.CLAIM, event.getReason());
		assertEquals(operation, event.getOperationId()); assertEquals(id, event.getVehicleId());
		assertSame(vehicle, event.getVehicle().orElseThrow()); assertSame(player, event.getPlayer().orElseThrow());
		assertEquals(actor, event.getActor().orElseThrow()); assertFalse(event.isStaff());
		assertEquals(target, event.getTarget().orElseThrow()); assertEquals("entry", event.getRawEntry().orElseThrow());
		assertSame(before, event.getPrevious()); assertSame(after, event.getCurrent());
		assertSame(VehicleOwnershipChangedEvent.getHandlerList(), event.getHandlers());
		VehicleOwnershipChangedEvent system = new VehicleOwnershipChangedEvent(false, OwnershipReason.MIGRATE, operation,
				id, null, null, null, false, null, null, before, before);
		assertFalse(system.isSuccess()); assertTrue(system.getVehicle().isEmpty()); assertTrue(system.getPlayer().isEmpty());
		assertTrue(system.getActor().isEmpty()); assertTrue(system.getTarget().isEmpty()); assertTrue(system.getRawEntry().isEmpty());
		assertThrows(NullPointerException.class, () -> new VehicleOwnershipChangedEvent(true, null, operation, id,
				null, null, null, false, null, null, before, after));
	}

	@Test
	void editCheckIsAllowedUntilAListenerDeniesIt() {
		VehicleOwnershipEditCheckEvent menu = new VehicleOwnershipEditCheckEvent(player, vehicle, null, null, true);
		assertTrue(menu.isMenu()); assertTrue(menu.getReason().isEmpty()); assertTrue(menu.getActor().isEmpty());
		assertTrue(menu.isStaff()); assertSame(player, menu.getPlayer()); assertSame(vehicle, menu.getVehicle());
		assertTrue(menu.isAllowed()); menu.deny(); assertFalse(menu.isAllowed());
		VehicleOwnershipEditCheckEvent toggle = new VehicleOwnershipEditCheckEvent(player, vehicle, OwnershipReason.TICKETS_TOGGLE, actor, false);
		assertFalse(toggle.isMenu()); assertEquals(OwnershipReason.TICKETS_TOGGLE, toggle.getReason().orElseThrow());
		assertEquals(actor, toggle.getActor().orElseThrow()); assertFalse(toggle.isStaff());
		assertSame(VehicleOwnershipEditCheckEvent.getHandlerList(), toggle.getHandlers());
		assertThrows(NullPointerException.class, () -> new VehicleOwnershipEditCheckEvent(player, null, null, null, false));
	}
}
