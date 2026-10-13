package net.tfminecraft.vehicleframework.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.tfminecraft.tlibs.character.CharacterRef;

class VehicleOwnersTest {
	private static final UUID CHARACTER = UUID.fromString("0b1f2e9c-6a3d-4c55-8a7e-2f1d9c3b4a51");
	private static final UUID ACCOUNT = UUID.fromString("5d7a9e2b-1c4f-4a3b-9e8d-7c6b5a4f3e21");

	@Test
	void characterOwnersRoundTripAndRejectLookalikes() {
		String owner = VehicleOwners.character(CHARACTER);
		assertEquals("char_" + CHARACTER, owner);
		assertEquals(Optional.of(CHARACTER), VehicleOwners.characterId(owner));
		assertEquals(Optional.of(CHARACTER), VehicleOwners.characterId("CHAR_" + CHARACTER.toString().toUpperCase()));
		for (String other : Arrays.asList(null, "none", "char_none", "char_", "char_1-1-1-1-1",
				"char_" + CHARACTER + "x", "char_" + CHARACTER.toString().replace('-', 'x'), "player_" + CHARACTER)) {
			assertTrue(VehicleOwners.characterId(other).isEmpty(), String.valueOf(other));
			assertFalse(VehicleOwners.isCharacterOwner(other));
		}
		assertThrows(NullPointerException.class, () -> VehicleOwners.character(null));
	}

	@Test
	void unownedLegacyAndPersonalOwnersFollowTheOwnerTable() {
		for (String unowned : Arrays.asList(null, "", " ", "none", "NONE")) assertTrue(VehicleOwners.isUnowned(unowned));
		for (String owned : List.of("char_none", "player_none", "player_Alex", VehicleOwners.character(CHARACTER))) {
			assertFalse(VehicleOwners.isUnowned(owned), owned);
		}
		assertTrue(VehicleOwners.isLegacyOwner("PLAYER_Alex"));
		for (String notLegacy : Arrays.asList(null, "player_", "player_none", "char_none", "Alex")) {
			assertFalse(VehicleOwners.isLegacyOwner(notLegacy), String.valueOf(notLegacy));
		}
		assertTrue(VehicleOwners.isPersonalOwner("player_Alex"));
		assertTrue(VehicleOwners.isPersonalOwner(VehicleOwners.character(CHARACTER)));
		assertFalse(VehicleOwners.isPersonalOwner("char_none"));
		assertFalse(VehicleOwners.isPersonalOwner("none"));
	}

	@Test
	void onlyCharacterModeValuesHaveACanonicalForm() {
		assertEquals(Optional.of("none"), VehicleOwners.canonical("None"));
		assertEquals(Optional.of("char_none"), VehicleOwners.canonical("CHAR_NONE"));
		assertEquals(Optional.of("char_" + CHARACTER), VehicleOwners.canonical("Char_" + CHARACTER.toString().toUpperCase()));
		for (String other : Arrays.asList(null, "", "player_Alex", "player_none", "faction_1")) {
			assertTrue(VehicleOwners.canonical(other).isEmpty(), String.valueOf(other));
		}
	}

	@Test
	void whitelistEntriesCarryTheCharacterAndTheCapturedName() {
		CharacterRef ref = new CharacterRef(ACCOUNT, CHARACTER);
		String entry = VehicleOwners.whitelistEntry(ref, "Kira; of the Vale");
		assertEquals("char_" + CHARACTER + ";" + ACCOUNT + ";Kira; of the Vale", entry);
		WhitelistEntry parsed = VehicleOwners.parseWhitelistEntry(entry).orElseThrow();
		assertEquals(ref, parsed.character());
		assertEquals("Kira; of the Vale", parsed.name());
		assertEquals(entry, parsed.encode());
		assertEquals("", VehicleOwners.parseWhitelistEntry(VehicleOwners.whitelistEntry(ref, null)).orElseThrow().name());
		assertThrows(NullPointerException.class, () -> VehicleOwners.whitelistEntry(null, "Kira"));
	}

	@Test
	void legacyAndDamagedWhitelistEntriesAreNotCharacters() {
		List<String> inert = new ArrayList<>(Arrays.asList(null, "player_Alex", "Alex", "char_none",
				"char_" + CHARACTER, "char_" + CHARACTER + ";" + ACCOUNT,
				"char_" + CHARACTER + ";not-a-uuid;Kira", "char_bad;" + ACCOUNT + ";Kira"));
		for (String entry : inert) {
			assertTrue(VehicleOwners.parseWhitelistEntry(entry).isEmpty(), String.valueOf(entry));
		}
	}

	@Test
	void reasonsKnowWhichAreSystemWrites() {
		for (OwnershipReason reason : OwnershipReason.values()) {
			boolean system = List.of("MIGRATE", "RESTAMP", "RELEASE", "HANDOVER", "CONSTRUCT", "TRANSFER").contains(reason.name());
			assertEquals(system, reason.isSystem(), reason.name());
		}
		for (SystemReason reason : SystemReason.values()) {
			assertEquals(reason.name(), reason.reason().name());
			assertTrue(reason.reason().isSystem());
		}
	}
}
