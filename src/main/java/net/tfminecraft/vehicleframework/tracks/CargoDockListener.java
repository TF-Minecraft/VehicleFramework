package net.tfminecraft.vehicleframework.tracks;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import net.tfminecraft.vehicleframework.VehicleFramework;
import net.tfminecraft.vehicleframework.cache.Cache;

/**
 * The dock tool. Right-clicking a storage block with it lets the click open the block as
 * usual; only if it really opens (no claim, lock or other plugin refused it) is the block
 * marked, and the window is not shown. So only a player who can open a container can make
 * it a dock.
 */
public final class CargoDockListener implements Listener {
	private record Pending(Block block, int tick) {
	}

	private final Map<UUID, Pending> pending = new HashMap<>();

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onInteract(PlayerInteractEvent event) {
		if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK
				|| !TrackTools.isDock(event.getItem())) {
			return;
		}
		Player player = event.getPlayer();
		Block block = event.getClickedBlock();
		if (block == null) {
			return;
		}
		if (!Cache.trainDocksEnabled || CargoDocks.storage(block) == null) {
			event.setCancelled(true);
			player.sendMessage(Cache.trainDocksEnabled ? "§cClick a chest, barrel, hopper or other storage block"
					: "§cCargo docks are turned off");
			return;
		}
		if (event.useInteractedBlock() == Event.Result.DENY) {
			player.sendMessage("§cYou cannot open that, so it cannot be your cargo dock");
			return;
		}
		if (player.isSneaking()) {
			event.setCancelled(true);
			player.sendMessage("§cStop sneaking to mark a cargo dock");
			return;
		}
		UUID id = player.getUniqueId();
		Pending click = new Pending(block, Bukkit.getCurrentTick());
		pending.put(id, click);
		Bukkit.getScheduler().runTaskLater(VehicleFramework.plugin, () -> {
			if (pending.remove(id, click)) {
				player.sendMessage("§cYou cannot open that, so it cannot be your cargo dock");
			}
		}, 2L);
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onOpen(InventoryOpenEvent event) {
		if (!(event.getPlayer() instanceof Player player)) {
			return;
		}
		Pending click = pending.remove(player.getUniqueId());
		if (click == null || Bukkit.getCurrentTick() - click.tick() > 1 || !opens(event, click.block())) {
			return;
		}
		event.setCancelled(true);
		CargoDocks.cycle(player, click.block());
	}

	// The window is the clicked block's, or the double chest it is half of.
	private static boolean opens(InventoryOpenEvent event, Block block) {
		Location at = event.getInventory().getLocation();
		return at != null && block.getWorld().equals(at.getWorld())
				&& at.distanceSquared(block.getLocation().add(0.5, 0.5, 0.5)) <= 2.25;
	}

	// A dock goes with its block, however the block goes; a block placed where a dock was
	// left behind does not inherit it.
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onBreak(BlockBreakEvent event) {
		forget(event.getBlock());
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPlace(BlockPlaceEvent event) {
		forget(event.getBlockPlaced());
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onBurn(BlockBurnEvent event) {
		forget(event.getBlock());
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onBlockExplode(BlockExplodeEvent event) {
		event.blockList().forEach(CargoDockListener::forget);
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onEntityExplode(EntityExplodeEvent event) {
		event.blockList().forEach(CargoDockListener::forget);
	}

	static void forget(Block block) {
		String world = block.getWorld().getName();
		if (DockRegistry.at(world, block.getX(), block.getY(), block.getZ()) != null) {
			DockRegistry.set(world, block.getX(), block.getY(), block.getZ(), null, null);
		}
	}
}
