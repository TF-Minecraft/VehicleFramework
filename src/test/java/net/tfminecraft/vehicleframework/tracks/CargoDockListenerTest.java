package net.tfminecraft.vehicleframework.tracks;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import net.tfminecraft.vehicleframework.VFLogger;
import net.tfminecraft.vehicleframework.cache.Cache;

class CargoDockListenerTest {
	@TempDir Path directory;
	private final List<AutoCloseable> scopes = new ArrayList<>();
	private final World world = mock(World.class);
	private final Player player = mock(Player.class);
	private final Block chest = mock(Block.class);
	private final ItemStack tool = mock(ItemStack.class);
	private final List<Runnable> later = new ArrayList<>();
	private final CargoDockListener listener = new CargoDockListener();
	private MockedStatic<Bukkit> bukkit;
	private MockedStatic<CargoDocks> docks;
	private boolean enabled;
	private int tick;

	@BeforeEach
	void setup() {
		enabled = Cache.trainDocksEnabled;
		Cache.trainDocksEnabled = true;
		keep(mockStatic(VFLogger.class));
		bukkit = keep(mockStatic(Bukkit.class));
		bukkit.when(Bukkit::getCurrentTick).thenAnswer(call -> tick);
		BukkitScheduler scheduler = mock(BukkitScheduler.class);
		bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
		when(scheduler.runTaskLater(any(), any(Runnable.class), eq(2L))).thenAnswer(call -> {
			later.add(call.getArgument(1));
			return null;
		});
		MockedStatic<TrackTools> tools = keep(mockStatic(TrackTools.class));
		tools.when(() -> TrackTools.isDock(tool)).thenReturn(true);
		docks = keep(mockStatic(CargoDocks.class));
		docks.when(() -> CargoDocks.storage(chest)).thenReturn(new CargoDocks.Storage(mock(Inventory.class), false));
		when(player.getUniqueId()).thenReturn(UUID.randomUUID());
		when(world.getName()).thenReturn("world");
		when(chest.getWorld()).thenReturn(world);
		when(chest.getX()).thenReturn(4);
		when(chest.getY()).thenReturn(64);
		when(chest.getZ()).thenReturn(-2);
		when(chest.getLocation()).thenAnswer(call -> new Location(world, 4, 64, -2));
		DockRegistry.load(directory.toFile());
	}

	@AfterEach
	void cleanup() throws Exception {
		DockRegistry.load(null);
		Cache.trainDocksEnabled = enabled;
		java.util.Collections.reverse(scopes);
		for (AutoCloseable scope : scopes) {
			scope.close();
		}
	}

	private <T extends AutoCloseable> T keep(T scope) {
		scopes.add(scope);
		return scope;
	}

	private PlayerInteractEvent click(ItemStack item, Block block, Action action, EquipmentSlot hand) {
		return new PlayerInteractEvent(player, action, item, block, BlockFace.UP, hand);
	}

	private InventoryOpenEvent open(HumanEntity who, Location at) {
		InventoryOpenEvent event = mock(InventoryOpenEvent.class);
		Inventory inventory = mock(Inventory.class);
		when(inventory.getLocation()).thenReturn(at);
		when(event.getInventory()).thenReturn(inventory);
		when(event.getPlayer()).thenReturn(who);
		return event;
	}

	@Test
	void aBlockThatReallyOpensIsMarkedAndItsWindowNotShown() {
		PlayerInteractEvent click = click(tool, chest, Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND);
		listener.onInteract(click);
		assertFalse(click.isCancelled());
		InventoryOpenEvent event = open(player, new Location(world, 5, 64, -2));
		listener.onOpen(event);
		verify(event).setCancelled(true);
		docks.verify(() -> CargoDocks.cycle(player, chest));
		// Answered: no complaint later.
		later.forEach(Runnable::run);
		verify(player, never()).sendMessage(anyString());
	}

	@Test
	void aBlockThatDoesNotOpenIsNotMarked() {
		listener.onInteract(click(tool, chest, Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND));
		later.forEach(Runnable::run);
		verify(player).sendMessage("§cYou cannot open that, so it cannot be your cargo dock");
		// Another window, a late one, or someone else's: nothing.
		listener.onOpen(open(mock(HumanEntity.class), null));
		listener.onInteract(click(tool, chest, Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND));
		listener.onOpen(open(player, null));
		listener.onInteract(click(tool, chest, Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND));
		listener.onOpen(open(player, new Location(mock(World.class), 4, 64, -2)));
		listener.onInteract(click(tool, chest, Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND));
		listener.onOpen(open(player, new Location(world, 9, 64, -2)));
		listener.onInteract(click(tool, chest, Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND));
		tick += 5;
		listener.onOpen(open(player, new Location(world, 4, 64, -2)));
		listener.onOpen(open(player, new Location(world, 4, 64, -2)));
		docks.verify(() -> CargoDocks.cycle(any(), any()), never());
	}

	@Test
	void refusedOrWrongClicksSayWhy() {
		listener.onInteract(click(tool, chest, Action.LEFT_CLICK_BLOCK, EquipmentSlot.HAND));
		listener.onInteract(click(tool, chest, Action.RIGHT_CLICK_BLOCK, EquipmentSlot.OFF_HAND));
		listener.onInteract(click(mock(ItemStack.class), chest, Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND));
		listener.onInteract(click(tool, null, Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND));
		listener.onInteract(click(tool, null, Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND));
		verify(player, never()).sendMessage(anyString());
		Block dirt = mock(Block.class);
		PlayerInteractEvent wrong = click(tool, dirt, Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND);
		listener.onInteract(wrong);
		assertTrue(wrong.isCancelled());
		verify(player).sendMessage("§cClick a chest, barrel, hopper or other storage block");
		Cache.trainDocksEnabled = false;
		listener.onInteract(click(tool, chest, Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND));
		verify(player).sendMessage("§cCargo docks are turned off");
		Cache.trainDocksEnabled = true;
		PlayerInteractEvent denied = click(tool, chest, Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND);
		denied.setUseInteractedBlock(Event.Result.DENY);
		listener.onInteract(denied);
		verify(player).sendMessage("§cYou cannot open that, so it cannot be your cargo dock");
		when(player.isSneaking()).thenReturn(true);
		listener.onInteract(click(tool, chest, Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND));
		verify(player).sendMessage("§cStop sneaking to mark a cargo dock");
		assertTrue(later.isEmpty());
	}

	@Test
	void breakingADockForgetsIt() {
		DockRegistry.set("world", 4, 64, -2, DockRegistry.Mode.LOAD, "o");
		Block other = mock(Block.class);
		when(other.getWorld()).thenReturn(world);
		listener.onBreak(new BlockBreakEvent(other, player));
		assertEquals(1, DockRegistry.size());
		listener.onBreak(new BlockBreakEvent(chest, player));
		assertEquals(0, DockRegistry.size());
		for (Runnable gone : List.<Runnable>of(
				() -> {
					org.bukkit.event.block.BlockPlaceEvent event = mock(org.bukkit.event.block.BlockPlaceEvent.class);
					when(event.getBlockPlaced()).thenReturn(chest);
					listener.onPlace(event);
				},
				() -> {
					org.bukkit.event.block.BlockBurnEvent event = mock(org.bukkit.event.block.BlockBurnEvent.class);
					when(event.getBlock()).thenReturn(chest);
					listener.onBurn(event);
				},
				() -> {
					org.bukkit.event.block.BlockExplodeEvent event = mock(org.bukkit.event.block.BlockExplodeEvent.class);
					when(event.blockList()).thenReturn(List.of(other, chest));
					listener.onBlockExplode(event);
				},
				() -> {
					org.bukkit.event.entity.EntityExplodeEvent event = mock(org.bukkit.event.entity.EntityExplodeEvent.class);
					when(event.blockList()).thenReturn(List.of(chest));
					listener.onEntityExplode(event);
				})) {
			DockRegistry.set("world", 4, 64, -2, DockRegistry.Mode.LOAD, "o");
			gone.run();
			assertEquals(0, DockRegistry.size());
		}
	}
}
