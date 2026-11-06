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
		return covers(world, minChunk(box.getMinX(), margin), maxChunk(box.getMaxX(), margin),
				minChunk(box.getMinZ(), margin), maxChunk(box.getMaxZ(), margin));
	}

	private static boolean covers(World world, int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
		for (int x = minChunkX; x <= maxChunkX; x++) {
			for (int z = minChunkZ; z <= maxChunkZ; z++) {
				if (!world.isChunkLoaded(x, z)) return false;
			}
		}
		return true;
	}

	private static int minChunk(double min, int margin) {
		return ((int) Math.floor(min) - margin) >> 4;
	}

	private static int maxChunk(double max, int margin) {
		return ((int) Math.floor(max) + margin) >> 4;
	}

	/**
	 * {@link LoadedArea#covers} for one vehicle, asked every tick. Once every chunk of an area is
	 * loaded, it stays loaded until one of them unloads, and Paper fires {@code ChunkUnloadEvent}
	 * for a chunk while it still counts as loaded. So the last covered area is remembered, and
	 * the same chunks or fewer are answered without asking the world again until the owner
	 * passes that unload to {@link #forget(World, int, int)}, and in any case at most
	 * {@link #MAX_REMEMBERED} times in a row. An area that is not covered is asked in full every
	 * time, so a vehicle resumes on the first tick its chunks are loaded.
	 */
	public static final class Memo {
		/** Answers from memory before asking the world again anyway, in case an unload went unheard. */
		static final int MAX_REMEMBERED = 100;
		// The covered area, in chunks; null while there is none.
		private World world;
		private int minX;
		private int maxX;
		private int minZ;
		private int maxZ;
		private int remembered;

		public boolean covers(World world, BoundingBox box, int margin) {
			int minChunkX = minChunk(box.getMinX(), margin);
			int maxChunkX = maxChunk(box.getMaxX(), margin);
			int minChunkZ = minChunk(box.getMinZ(), margin);
			int maxChunkZ = maxChunk(box.getMaxZ(), margin);
			if (world.equals(this.world) && minChunkX >= minX && maxChunkX <= maxX
					&& minChunkZ >= minZ && maxChunkZ <= maxZ && ++remembered < MAX_REMEMBERED) {
				return true;
			}
			this.world = null;
			if (!LoadedArea.covers(world, minChunkX, maxChunkX, minChunkZ, maxChunkZ)) {
				return false;
			}
			this.world = world;
			remembered = 0;
			minX = minChunkX;
			maxX = maxChunkX;
			minZ = minChunkZ;
			maxZ = maxChunkZ;
			return true;
		}

		/** A chunk is unloading: the next question about an area with it in asks the world. */
		public void forget(World world, int chunkX, int chunkZ) {
			if (this.world != null && this.world.equals(world) && chunkX >= minX && chunkX <= maxX
					&& chunkZ >= minZ && chunkZ <= maxZ) {
				this.world = null;
			}
		}

		/** A world is unloading, perhaps without an unload event for each chunk. */
		public void forget(World world) {
			if (this.world != null && this.world.equals(world)) {
				this.world = null;
			}
		}
	}
}
