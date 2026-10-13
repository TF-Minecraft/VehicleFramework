package net.tfminecraft.vehicleframework.ownership;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.CharacterStatus;
import net.tfminecraft.tlibs.character.Characters;
import net.tfminecraft.vehicleframework.VFLogger;

class CharacterLookupTest {
	private MockedStatic<Characters> characters;
	private MockedStatic<VFLogger> log;

	/** Stands in for TLibs 2.5.0's facade. */
	public static final class WithFindByName {
		static final List<Object> results = new ArrayList<>();
		static String asked;

		public static List<Object> findByName(String name) {
			asked = name;
			if (name.equals("boom")) throw new IllegalStateException("provider failed");
			return results;
		}
	}

	/** A same-named method with the wrong return type is not the lookup. */
	public static final class WrongReturn {
		public static String findByName(String name) {
			return name;
		}
	}

	static CharacterInfo info(String name, CharacterStatus status) {
		return new CharacterInfo(new CharacterRef(UUID.randomUUID(), UUID.randomUUID()), name, status, null, Set.of(), 0, Instant.EPOCH);
	}

	@BeforeEach
	void setup() {
		characters = mockStatic(Characters.class);
		log = mockStatic(VFLogger.class);
		WithFindByName.results.clear();
	}

	@AfterEach
	void cleanup() {
		OwnershipFixture.lookup(null);
		log.close();
		characters.close();
	}

	@Test
	void sessionsCountOnlyWhileTheProviderSaysTheyAreCurrent() {
		Player player = mock(Player.class);
		CharacterSession session = OwnershipFixture.session("acct", "kira", 1);
		assertTrue(CharacterLookup.session(null).isEmpty());
		assertTrue(CharacterLookup.session(player).isEmpty());
		characters.when(() -> Characters.currentSession(player)).thenReturn(Optional.of(session));
		assertTrue(CharacterLookup.session(player).isEmpty());
		characters.when(() -> Characters.isCurrent(session)).thenReturn(true);
		assertEquals(session, CharacterLookup.session(player).orElseThrow());
		assertTrue(CharacterLookup.isCurrent(session));
		assertFalse(CharacterLookup.isCurrent(null));
	}

	@Test
	void infoAndDisplayNamesComeFromTheProvider() {
		Player player = mock(Player.class);
		CharacterInfo kira = info("Kira", CharacterStatus.ALIVE);
		characters.when(() -> Characters.info(kira.ref().characterId())).thenReturn(Optional.of(kira));
		characters.when(() -> Characters.displayName(player)).thenReturn("Masked");
		assertSame(kira, CharacterLookup.info(kira.ref().characterId()).orElseThrow());
		assertTrue(CharacterLookup.info(null).isEmpty());
		assertEquals("Masked", CharacterLookup.displayName(player));
	}

	@Test
	void typedNamesAreOffWithoutFindByNameAndTheProbeIsLoggedOnce() {
		OwnershipFixture.lookup(null);
		assertFalse(CharacterLookup.typedNamesAvailable());
		assertFalse(CharacterLookup.typedNamesAvailable());
		log.verify(() -> VFLogger.info(contains("typed whitelist names are off")), times(1));
		assertTrue(CharacterLookup.findLiving("Kira").isEmpty());
		OwnershipFixture.lookup(WrongReturn.class);
		assertFalse(CharacterLookup.typedNamesAvailable());
	}

	@Test
	void typedLookupKeepsOnlyLivingCharactersAndSurvivesProviderFailures() {
		OwnershipFixture.lookup(WithFindByName.class);
		assertTrue(CharacterLookup.typedNamesAvailable());
		CharacterInfo alive = info("Kira", CharacterStatus.ALIVE), dead = info("Kira", CharacterStatus.DEAD);
		WithFindByName.results.addAll(List.of(alive, dead, "not a character"));
		assertEquals(List.of(alive), CharacterLookup.findLiving("  Kira "));
		assertEquals("Kira", WithFindByName.asked);
		assertTrue(CharacterLookup.findLiving(" ").isEmpty());
		assertTrue(CharacterLookup.findLiving(null).isEmpty());
		assertTrue(CharacterLookup.findLiving("boom").isEmpty());
		log.verify(() -> VFLogger.log(contains("Character name lookup failed")));
	}
}
