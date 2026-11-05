package net.tfminecraft.vehicleframework.tracks;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.vehicleframework.VFLogger;

/**
 * Cargo docks: storage blocks a tape-driven train loads from or unloads into when it stops
 * beside them. Kept in {@code docks.yml} and indexed by chunk, so a stop only looks at the
 * few chunks around each car.
 */
public final class DockRegistry {
	public enum Mode {
		LOAD, UNLOAD;

		/** load, then unload, then off (null). */
		public Mode next() {
			return this == LOAD ? UNLOAD : null;
		}

		static Mode parse(String raw) {
			try {
				return raw == null ? null : Mode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				return null;
			}
		}
	}

	/** A storage block marked as a dock, and the owner identity of the player who marked it. */
	public record Dock(String world, int x, int y, int z, Mode mode, String owner) {
		String key() {
			return DockRegistry.key(world, x, y, z);
		}

		double distanceSquared(double px, double py, double pz) {
			double dx = x + 0.5 - px;
			double dy = y + 0.5 - py;
			double dz = z + 0.5 - pz;
			return dx * dx + dy * dy + dz * dz;
		}
	}

	private static final Map<String, Dock> docks = new LinkedHashMap<>();
	private static final Map<String, Map<Long, List<Dock>>> byChunk = new HashMap<>();
	private static File file;

	private DockRegistry() {
	}

	/** Reads {@code docks.yml}; malformed entries are skipped with a log line. */
	public static void load(File dataFolder) {
		docks.clear();
		byChunk.clear();
		file = dataFolder == null ? null : new File(dataFolder, "docks.yml");
		if (file == null || !file.isFile()) {
			return;
		}
		YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
		ConfigurationSection section = config.getConfigurationSection("docks");
		if (section == null) {
			return;
		}
		for (String id : section.getKeys(false)) {
			ConfigurationSection entry = section.getConfigurationSection(id);
			Mode mode = entry == null ? null : Mode.parse(entry.getString("mode"));
			String world = entry == null ? null : entry.getString("world");
			if (mode == null || world == null || world.isBlank()) {
				VFLogger.log("Skipping malformed dock " + id + " in docks.yml");
				continue;
			}
			index(new Dock(world, entry.getInt("x"), entry.getInt("y"), entry.getInt("z"), mode,
					entry.getString("owner", "none")));
		}
	}

	public static Dock at(String world, int x, int y, int z) {
		return docks.get(key(world, x, y, z));
	}

	/** Marks or changes a dock; a null mode removes it. Saves at once. */
	public static Dock set(String world, int x, int y, int z, Mode mode, String owner) {
		Dock old = docks.remove(key(world, x, y, z));
		if (old != null) {
			unindex(old);
		}
		Dock dock = mode == null ? null : new Dock(world, x, y, z, mode, owner == null ? "none" : owner);
		if (dock != null) {
			index(dock);
		}
		if (old != null || dock != null) {
			save();
		}
		return dock;
	}

	/** Docks whose block centre is within {@code reach} of a point. */
	public static List<Dock> near(String world, double x, double y, double z, double reach) {
		Map<Long, List<Dock>> chunks = byChunk.get(world);
		if (chunks == null || reach < 0) {
			return List.of();
		}
		List<Dock> found = new ArrayList<>();
		int minX = (int) Math.floor(x - reach) >> 4;
		int maxX = (int) Math.floor(x + reach) >> 4;
		int minZ = (int) Math.floor(z - reach) >> 4;
		int maxZ = (int) Math.floor(z + reach) >> 4;
		for (int cx = minX; cx <= maxX; cx++) {
			for (int cz = minZ; cz <= maxZ; cz++) {
				for (Dock dock : chunks.getOrDefault(chunkKey(cx, cz), List.of())) {
					if (dock.distanceSquared(x, y, z) <= reach * reach) {
						found.add(dock);
					}
				}
			}
		}
		return found;
	}

	public static int size() {
		return docks.size();
	}

	private static void index(Dock dock) {
		docks.put(dock.key(), dock);
		byChunk.computeIfAbsent(dock.world(), w -> new HashMap<>())
				.computeIfAbsent(chunkKey(dock.x() >> 4, dock.z() >> 4), k -> new ArrayList<>()).add(dock);
	}

	private static void unindex(Dock dock) {
		Map<Long, List<Dock>> chunks = byChunk.get(dock.world());
		long chunk = chunkKey(dock.x() >> 4, dock.z() >> 4);
		List<Dock> list = chunks.get(chunk);
		list.remove(dock);
		if (list.isEmpty()) {
			chunks.remove(chunk);
		}
	}

	// A few lines per dock, written when a player marks one; small enough to write at once.
	static void save() {
		if (file == null) {
			return;
		}
		YamlConfiguration config = new YamlConfiguration();
		int i = 0;
		for (Dock dock : docks.values()) {
			String path = "docks.d" + i++;
			config.set(path + ".world", dock.world());
			config.set(path + ".x", dock.x());
			config.set(path + ".y", dock.y());
			config.set(path + ".z", dock.z());
			config.set(path + ".mode", dock.mode().name().toLowerCase(Locale.ROOT));
			config.set(path + ".owner", dock.owner());
		}
		try {
			config.save(file);
		} catch (IOException e) {
			VFLogger.log("Could not save docks.yml: " + e.getMessage());
		}
	}

	static String key(String world, int x, int y, int z) {
		return world + ";" + x + ";" + y + ";" + z;
	}

	static long chunkKey(int cx, int cz) {
		return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
	}
}
