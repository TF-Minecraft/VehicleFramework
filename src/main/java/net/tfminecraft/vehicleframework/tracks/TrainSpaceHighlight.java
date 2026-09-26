package net.tfminecraft.vehicleframework.tracks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import net.tfminecraft.vehicleframework.VehicleFramework;

/**
 * Outlines blocks that are in the way of trains, for one player only. The outline
 * glows through walls, follows changes to its block, and is removed after a minute.
 */
public final class TrainSpaceHighlight {
	public static final int MAX_SHOWN = 300;
	private static final long SHOW_TICKS = 20L * 60;
	private static final long REFRESH_TICKS = 5;
	private static final Map<UUID, Shown> shown = new ConcurrentHashMap<>();

	private record Shown(List<BlockDisplay> displays, BukkitTask refresh) {
		void remove() {
			refresh.cancel();
			displays.forEach(BlockDisplay::remove);
		}
	}

	private TrainSpaceHighlight() {
	}

	/** Shows up to {@link #MAX_SHOWN} of the blocks nearest the player. Returns how many are shown. */
	public static int show(Player player, List<TrainBlockCollision.Obstruction> blocks) {
		clear(player);
		World world = player.getWorld();
		Location at = player.getLocation();
		List<TrainBlockCollision.Obstruction> nearest = new ArrayList<>(blocks);
		nearest.sort(Comparator.comparingDouble(o -> at.distanceSquared(
				new Location(world, o.x() + 0.5, o.y() + 0.5, o.z() + 0.5))));
		List<BlockDisplay> displays = new ArrayList<>();
		for (TrainBlockCollision.Obstruction o : nearest.subList(0, Math.min(MAX_SHOWN, nearest.size()))) {
			Block block = world.getBlockAt(o.x(), o.y(), o.z());
			BlockDisplay display = world.spawn(block.getLocation(), BlockDisplay.class, d -> {
				d.setBlock(block.getBlockData());
				d.setPersistent(false);
				d.setVisibleByDefault(false);
				d.setGlowing(true);
				d.setGlowColorOverride(Color.RED);
				d.setBrightness(new Display.Brightness(15, 15));
				// A touch larger than the block so the outline is not hidden inside it.
				d.setTransformation(new Transformation(
						new Vector3f(-0.01f, -0.01f, -0.01f), new AxisAngle4f(),
						new Vector3f(1.02f, 1.02f, 1.02f), new AxisAngle4f()));
			});
			player.showEntity(VehicleFramework.plugin, display);
			displays.add(display);
		}
		BukkitTask refresh = Bukkit.getScheduler().runTaskTimer(
				VehicleFramework.plugin, () -> refresh(displays), REFRESH_TICKS, REFRESH_TICKS);
		Shown set = new Shown(displays, refresh);
		shown.put(player.getUniqueId(), set);
		Bukkit.getScheduler().runTaskLater(VehicleFramework.plugin, () -> {
			// Only clear this set; a later command may have replaced it.
			if (shown.remove(player.getUniqueId(), set)) {
				set.remove();
			}
		}, SHOW_TICKS);
		return displays.size();
	}

	/**
	 * Drops outlines of blocks that are gone or no longer solid, such as mined ones, and
	 * redraws outlines of blocks replaced by another solid block.
	 */
	static void refresh(List<BlockDisplay> displays) {
		Iterator<BlockDisplay> it = displays.iterator();
		while (it.hasNext()) {
			BlockDisplay display = it.next();
			if (!display.isValid()) {
				it.remove();
				continue;
			}
			Block block = display.getLocation().getBlock();
			if (block.isPassable()) {
				display.remove();
				it.remove();
			} else if (!block.getBlockData().equals(display.getBlock())) {
				display.setBlock(block.getBlockData());
			}
		}
	}

	public static void clear(Player player) {
		Shown set = shown.remove(player.getUniqueId());
		if (set != null) {
			set.remove();
		}
	}

	public static void clearAll() {
		shown.values().forEach(Shown::remove);
		shown.clear();
	}
}
