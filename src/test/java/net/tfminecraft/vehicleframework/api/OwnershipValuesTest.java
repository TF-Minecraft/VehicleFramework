package net.tfminecraft.vehicleframework.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.tfminecraft.tlibs.character.CharacterRef;

class OwnershipValuesTest {
	private static final UUID CHARACTER = UUID.fromString("0b1f2e9c-6a3d-4c55-8a7e-2f1d9c3b4a51");

	@Test
	void valuesDefaultTheOwnerAndKeepAnImmutableCopyOfTheEntries() {
		List<String> entries = new ArrayList<>(Arrays.asList("a", null, "b"));
		OwnershipValues values = new OwnershipValues(" ", entries, true, true, "ticket");
		entries.add("c");
		assertEquals("none", values.owner());
		assertEquals(List.of("a", "b"), values.whitelist());
		assertThrows(UnsupportedOperationException.class, () -> values.whitelist().add("x"));
		assertEquals(List.of(), new OwnershipValues(null, null, false, false, null).whitelist());
		assertEquals("none", new OwnershipValues(null, null, false, false, null).owner());
	}

	@Test
	void metaKeepsStoredAndLiveApartAndPrefersLiveForTheCurrentView() {
		OwnershipValues stored = new OwnershipValues("char_none", List.of(), false, false, null);
		OwnershipValues live = new OwnershipValues("none", List.of(), false, false, null);
		OwnershipMeta both = new OwnershipMeta(CHARACTER, "cart", "Cart", "char_none", Optional.of(stored), Optional.of(live));
		assertTrue(both.hasStoredRow()); assertTrue(both.isLive()); assertSame(live, both.current().orElseThrow());
		OwnershipMeta storedOnly = new OwnershipMeta(CHARACTER, "cart", "Cart", "char_none", Optional.of(stored), null);
		assertFalse(storedOnly.isLive()); assertSame(stored, storedOnly.current().orElseThrow());
		OwnershipMeta liveOnly = new OwnershipMeta(CHARACTER, "cart", "Cart", null, null, Optional.of(live));
		assertFalse(liveOnly.hasStoredRow()); assertTrue(liveOnly.stored().isEmpty());
		assertThrows(NullPointerException.class, () -> new OwnershipMeta(null, "cart", "Cart", null, null, null));
	}

	@Test
	void ownerChangesAcceptOnlyCharacterModeValuesInCanonicalForm() {
		assertEquals("char_" + CHARACTER, OwnerChange.character(CHARACTER).owner());
		assertEquals("char_none", OwnerChange.noCharacter().owner());
		assertEquals("none", OwnerChange.unowned().owner());
		assertNull(OwnerChange.unowned().whitelist());
		assertEquals("char_" + CHARACTER, new OwnerChange("CHAR_" + CHARACTER.toString().toUpperCase(), null).owner());
		List<String> seed = new ArrayList<>(List.of("player_Old"));
		OwnerChange seeded = OwnerChange.seeded("char_" + CHARACTER, seed);
		seed.clear();
		assertEquals(List.of("player_Old"), seeded.whitelist());
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> new OwnerChange("player_Alex", null));
		assertTrue(refused.getMessage().contains("player_Alex"));
		assertThrows(NullPointerException.class, () -> new OwnerChange(null, null));
		assertThrows(NullPointerException.class, () -> OwnerChange.seeded("none", null));
	}

	@Test
	void entriesAndQuarantineRowsNormaliseMissingText() {
		CharacterRef ref = new CharacterRef(UUID.randomUUID(), CHARACTER);
		assertEquals("", new WhitelistEntry(ref, null).name());
		assertThrows(NullPointerException.class, () -> new WhitelistEntry(null, "Kira"));
		QuarantinedVehicle row = new QuarantinedVehicle(CHARACTER, null, null);
		assertEquals("", row.owner()); assertEquals("", row.reason());
		assertThrows(NullPointerException.class, () -> new QuarantinedVehicle(null, "a", "b"));
		assertTrue(new VehicleOwnershipAuthority() { }.ready());
	}
}
