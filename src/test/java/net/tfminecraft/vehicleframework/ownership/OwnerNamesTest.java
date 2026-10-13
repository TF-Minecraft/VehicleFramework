package net.tfminecraft.vehicleframework.ownership;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.CharacterStatus;
import net.tfminecraft.tlibs.character.Characters;
import net.tfminecraft.vehicleframework.api.OwnershipIdentity;
import net.tfminecraft.vehicleframework.api.VehicleOwners;

class OwnerNamesTest {
	private MockedStatic<Bukkit> bukkit;
	private MockedStatic<Characters> characters;
	private final CharacterSession kira = OwnershipFixture.session("steve", "kira", 1);

	@BeforeEach
	void setup() {
		bukkit = mockStatic(Bukkit.class);
		characters = mockStatic(Characters.class);
	}

	@AfterEach
	void cleanup() {
		characters.close(); bukkit.close();
		OwnershipFixture.mode(OwnershipIdentity.NAMES);
	}

	@Test
	void ownersReadAsCharacterNamesWithTheAccountForStaffOnly() {
		String owner = VehicleOwners.character(kira.ref().characterId());
		assertEquals("an unknown character", OwnerNames.owner(owner, false));
		characters.when(() -> Characters.info(kira.ref().characterId())).thenReturn(Optional.of(
				new CharacterInfo(kira.ref(), "Kira", CharacterStatus.DEAD, null, Set.of(), 0, null)));
		assertEquals("Kira", OwnerNames.owner(owner, false));
		OfflinePlayer account = mock(OfflinePlayer.class);
		bukkit.when(() -> Bukkit.getOfflinePlayer(kira.ref().ownerId())).thenReturn(account);
		assertEquals("Kira (" + kira.ref().ownerId() + ")", OwnerNames.owner(owner, true));
		when(account.getName()).thenReturn("Steve");
		assertEquals("Kira (Steve)", OwnerNames.owner(owner, true));
		assertEquals("nobody", OwnerNames.owner("none", true));
		assertEquals("no leader", OwnerNames.owner("CHAR_NONE", true));
		assertEquals("Steve", OwnerNames.owner("Player_Steve", false));
		assertEquals("faction_1", OwnerNames.owner("faction_1", false));
	}

	@Test
	void onlinePlayersShowTheirDisplayNameInCharactersMode() {
		Player player = mock(Player.class);
		when(player.getName()).thenReturn("Steve");
		characters.when(() -> Characters.displayName(player)).thenReturn("Hooded figure");
		assertEquals("Steve", OwnerNames.player(player));
		OwnershipFixture.mode(OwnershipIdentity.CHARACTERS);
		assertEquals("Hooded figure", OwnerNames.player(player));
	}
}
