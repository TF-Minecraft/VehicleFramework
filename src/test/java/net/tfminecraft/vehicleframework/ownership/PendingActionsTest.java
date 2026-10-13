package net.tfminecraft.vehicleframework.ownership;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.Characters;
import net.tfminecraft.vehicleframework.ownership.PendingActions.Kind;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;

class PendingActionsTest {
	private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
	private final List<Runnable> timeouts = new ArrayList<>();
	private final List<Long> delays = new ArrayList<>();
	private final Player player = mock(Player.class);
	private final PendingActions pending = new PendingActions();
	private MockedStatic<Bukkit> bukkit;
	private MockedStatic<Characters> characters;

	@BeforeEach
	void setup() {
		bukkit = mockStatic(Bukkit.class);
		characters = mockStatic(Characters.class);
		bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
		when(scheduler.runTaskLater(any(), any(Runnable.class), anyLong())).thenAnswer(call -> {
			timeouts.add(call.getArgument(1)); delays.add(call.getArgument(2));
			BukkitTask task = mock(BukkitTask.class); when(task.getTaskId()).thenReturn(timeouts.size()); return task;
		});
		when(player.getUniqueId()).thenReturn(UUID.randomUUID());
	}

	@AfterEach
	void cleanup() {
		characters.close();
		bukkit.close();
	}

	@Test
	void actionsExpireAfterSixtySecondsWithTheirOwnMessage() {
		ActiveVehicle vehicle = mock(ActiveVehicle.class);
		pending.start(player, Kind.WHITELIST_ADD, vehicle, null, null);
		assertEquals(List.of(1200L), delays);
		assertTrue(pending.has(player, Kind.WHITELIST_ADD)); assertFalse(pending.has(player, Kind.RENAME));
		timeouts.getFirst().run();
		assertFalse(pending.has(player, Kind.WHITELIST_ADD));
		verify(player).sendMessage("§cWhitelist entry timed out.");
		timeouts.getFirst().run();
		verify(player, times(1)).sendMessage(anyString());
	}

	@Test
	void restartingOrTakingAnActionCancelsItsOldTimeout() {
		pending.start(player, Kind.RENAME, null, null, null);
		pending.start(player, Kind.RENAME, null, null, null);
		verify(scheduler).cancelTask(1);
		timeouts.getFirst().run();
		verify(player, never()).sendMessage(anyString());
		assertTrue(pending.take(player, Kind.RENAME).isPresent());
		verify(scheduler).cancelTask(2);
		timeouts.get(1).run();
		verify(player, never()).sendMessage(anyString());
		assertTrue(pending.take(player, Kind.RENAME).isEmpty());
	}

	@Test
	void clearingDropsEveryKindAndReportsWhetherAnyWerePending() {
		assertFalse(pending.clear(player));
		pending.start(player, Kind.TAKEOVER, null, null, new PendingActions.Target("char_x", null, "Kira"));
		pending.start(player, Kind.RENAME, null, null, null);
		assertTrue(pending.clear(player));
		assertFalse(pending.has(player, Kind.TAKEOVER)); assertFalse(pending.has(player, Kind.RENAME));
		pending.start(player, Kind.TAKEOVER, null, null, null);
		pending.cancel(player, Kind.TAKEOVER);
		assertFalse(pending.has(player, Kind.TAKEOVER));
		timeouts.getLast().run();
		verify(player, never()).sendMessage("§cTakeover expired.");
	}

	@Test
	void anActionStaysValidOnlyWhileItsSessionIsCurrent() {
		CharacterSession session = OwnershipFixture.session("alex", "kira", 7);
		pending.start(player, Kind.RENAME, null, session, null);
		PendingActions.Pending action = pending.take(player, Kind.RENAME).orElseThrow();
		assertSame(session, action.session());
		assertFalse(PendingActions.stillCurrent(action));
		characters.when(() -> Characters.isCurrent(session)).thenReturn(true);
		assertTrue(PendingActions.stillCurrent(action));
		pending.start(player, Kind.RENAME, null, null, null);
		assertTrue(PendingActions.stillCurrent(pending.take(player, Kind.RENAME).orElseThrow()), "names mode has no session");
	}
}
