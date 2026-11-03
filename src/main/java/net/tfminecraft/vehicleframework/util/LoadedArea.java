package net.tfminecraft.vehicleframework.util;

import org.bukkit.World;
import org.bukkit.util.BoundingBox;

/** Whether the chunks around a box are loaded, asked without loading any of them. */
public final class LoadedArea {

	private LoadedArea() {}

	/**
	 * Whether every chunk under {@code box} grown by {@code margin} blocks in x and z is fully
	 * loaded. {@link World#isChunkLoaded(int, int)} never loads a chunk, unlike a block read.
	 */
	public static boolean covers(World world, BoundingBox box, int margin) {
		int minChunkX = ((int) Math.floor(box.getMinX()) - margin) >> 4;
		int maxChunkX = ((int) Math.floor(box.getMaxX()) + margin) >> 4;
		int minChunkZ = ((int) Math.floor(box.getMinZ()) - margin) >> 4;
		int maxChunkZ = ((int) Math.floor(box.getMaxZ()) + margin) >> 4;
		for (int x = minChunkX; x <= maxChunkX; x++) {
			for (int z = minChunkZ; z <= maxChunkZ; z++) {
				if (!world.isChunkLoaded(x, z)) return false;
			}
		}
		return true;
	}
}
