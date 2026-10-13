package net.tfminecraft.vehicleframework.ownership;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.Characters;
import net.tfminecraft.vehicleframework.api.OwnershipIdentity;
import net.tfminecraft.vehicleframework.api.VehicleOwners;
import net.tfminecraft.vehicleframework.data.OwnerData;
import net.tfminecraft.vehicleframework.enums.SeatType;
import net.tfminecraft.vehicleframework.vehicles.handlers.VehicleTicketRules;

class OwnershipAccessTest {
	private MockedStatic<Characters> characters;
	private final Player alex = mock(Player.class);
	private final CharacterSession kira = OwnershipFixture.session("alex", "kira", 1);
	private final CharacterSession bren = OwnershipFixture.session("alex", "bren", 2);

	@BeforeEach
	void setup() {
		characters = mockStatic(Characters.class);
		when(alex.getName()).thenReturn("Alex");
	}

	@AfterEach
	void cleanup() {
		characters.close();
		OwnershipFixture.mode(OwnershipIdentity.NAMES);
	}

	private void plays(CharacterSession session) {
		characters.when(() -> Characters.currentSession(alex)).thenReturn(Optional.of(session));
		characters.when(() -> Characters.isCurrent(session)).thenReturn(true);
	}

	@Test
	void namesModeKeepsAccountNameOwnershipAndLegacyBareEntries() {
		OwnerData data = new OwnerData();
		data.setOwner("PLAYER_alex");
		assertTrue(OwnershipAccess.isOwner(alex, data));
		assertEquals("player_Alex", OwnershipAccess.ownerValue(alex).orElseThrow());
		assertTrue(OwnershipAccess.session(alex).isEmpty());
		data.setOwner("char_" + kira.ref().characterId());
		assertFalse(OwnershipAccess.isOwner(alex, data));
		data.setWhiteList(Arrays.asList("alex", VehicleOwners.whitelistEntry(kira.ref(), "Kira")));
		assertTrue(VehicleTicketRules.ownerOrWhitelisted(data, OwnershipAccess.key(alex)));
		characters.verifyNoInteractions();
		assertNull(OwnershipAccess.key(null));
		assertFalse(OwnershipAccess.isOwner(alex, null));
	}

	@Test
	void charactersModeMatchesTheCurrentCharacterAndTreatsItsSiblingAsAStranger() {
		OwnershipFixture.mode(OwnershipIdentity.CHARACTERS);
		OwnerData data = new OwnerData();
		data.setOwner(VehicleOwners.character(kira.ref().characterId()));
		data.setWhiteListed(true);
		plays(kira);
		assertTrue(OwnershipAccess.isOwner(alex, data));
		assertEquals(kira, OwnershipAccess.session(alex).orElseThrow());
		plays(bren);
		assertFalse(OwnershipAccess.isOwner(alex, data));
		assertFalse(VehicleTicketRules.mayOpenSeatMenu(data, data, OwnershipAccess.key(alex), false));
		data.setWhiteList(List.of(VehicleOwners.whitelistEntry(bren.ref(), "Bren")));
		assertTrue(VehicleTicketRules.mayOpenSeatMenu(data, data, OwnershipAccess.key(alex), false));
	}

	@Test
	void charactersModeNeverMatchesLegacyEntriesOrTheNoLeaderStamp() {
		OwnershipFixture.mode(OwnershipIdentity.CHARACTERS);
		plays(kira);
		OwnerData data = new OwnerData();
		data.setOwner("player_Alex");
		data.setWhiteListed(true);
		data.setWhiteList(List.of("player_Alex", "Alex"));
		assertFalse(OwnershipAccess.isOwner(alex, data));
		assertFalse(VehicleTicketRules.ownerOrWhitelisted(data, OwnershipAccess.key(alex)));
		data.setOwner(VehicleOwners.NO_CHARACTER);
		assertFalse(VehicleTicketRules.mayOpenSeatMenu(data, data, OwnershipAccess.key(alex), false));
	}

	@Test
	void charactersModeFailsClosedWithoutAProviderOrSession() {
		OwnershipFixture.mode(OwnershipIdentity.CHARACTERS);
		OwnerData data = new OwnerData();
		data.setOwner(VehicleOwners.character(kira.ref().characterId()));
		data.setWhiteListed(true);
		characters.when(() -> Characters.currentSession(alex)).thenReturn(Optional.empty());
		assertNull(OwnershipAccess.key(alex));
		assertTrue(OwnershipAccess.ownerValue(alex).isEmpty());
		assertFalse(OwnershipAccess.isOwner(alex, data));
		assertFalse(VehicleTicketRules.mayOpenSeatMenu(data, data, OwnershipAccess.key(alex), false));
		data.setTicketsEnabled(true);
		assertTrue(VehicleTicketRules.mayOpenSeatMenu(data, data, OwnershipAccess.key(alex), true));
		assertFalse(VehicleTicketRules.mayEnter(data, data, OwnershipAccess.key(alex), SeatType.PASSENGER, false));
		characters.when(() -> Characters.currentSession(alex)).thenReturn(Optional.of(kira));
		assertNull(OwnershipAccess.key(alex), "a session the provider no longer calls current does not count");
	}

	@Test
	void disabledModeHasNoIdentityAndUnownedVehiclesStayOpen() {
		OwnershipFixture.mode(OwnershipIdentity.DISABLED);
		OwnerData data = new OwnerData();
		data.setWhiteListed(true);
		assertNull(OwnershipAccess.key(alex));
		assertTrue(VehicleTicketRules.mayOpenSeatMenu(data, data, OwnershipAccess.key(alex), false));
		data.setOwner("player_Alex");
		assertFalse(VehicleTicketRules.mayOpenSeatMenu(data, data, OwnershipAccess.key(alex), false));
		characters.verifyNoInteractions();
	}

	@Test
	void accessKeysCompareOwnersWithoutCase() {
		AccessKey key = AccessKey.character(kira.ref().characterId());
		assertTrue(key.owns(("CHAR_" + kira.ref().characterId()).toUpperCase()));
		assertFalse(key.owns(null));
		assertFalse(key.matchesEntry(null));
		assertNull(AccessKey.name(null));
		assertFalse(AccessKey.name("Alex").matchesEntry(null));
	}
}
