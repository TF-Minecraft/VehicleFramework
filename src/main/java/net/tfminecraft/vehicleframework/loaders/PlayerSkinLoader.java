package net.tfminecraft.vehicleframework.loaders;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.vehicleframework.VFLogger;
import net.tfminecraft.vehicleframework.vehicles.Vehicle;
import net.tfminecraft.vehicleframework.vehicles.handlers.skins.VehicleSkin;

/**
 * Loads website vehicle skins from {@code player-skins.yml} into the vehicle registry.
 *
 * <p>ArmourShop writes the file when it applies an approved upload. Each entry adds one skin to
 * an existing vehicle type; the vehicle's own YAML is never touched. Spawned vehicles share their
 * type's skin map, so a reload here reaches them without unloading anything.
 *
 * <pre>
 * vskin_drumleaf_sunrise:
 *   vehicle: karve
 *   name: "Sunrise"
 *   model: vskin_drumleaf_sunrise
 *   owner: 3f2a7c1e-...   # the uploader's account UUID
 * </pre>
 */
public final class PlayerSkinLoader {

	public static final String FILE_NAME = "player-skins.yml";
	private static final Pattern ID = Pattern.compile("[a-z0-9_]+");

	private PlayerSkinLoader() {}

	/** Result counts of one load. */
	public static final class Result {
		public final int loaded;
		public final int skipped;
		public final boolean fileError;

		public Result(int loaded, int skipped, boolean fileError) {
			this.loaded = loaded;
			this.skipped = skipped;
			this.fileError = fileError;
		}
	}

	/**
	 * Replaces every player skin in {@code vehicles} with the entries in {@code file}. A file
	 * that cannot be read keeps the skins already loaded.
	 */
	public static Result apply(File file, Map<String, Vehicle> vehicles) {
		YamlConfiguration config = new YamlConfiguration();
		if (file != null && file.isFile()) {
			try {
				config.load(file);
			} catch (IOException | InvalidConfigurationException e) {
				VFLogger.log("Failed to read " + file.getName() + "; keeping the loaded player skins: " + e.getMessage());
				return new Result(0, 0, true);
			}
		}
		for (Vehicle vehicle : vehicles.values()) {
			vehicle.getSkinHandler().removePlayerSkins();
		}
		int loaded = 0;
		int skipped = 0;
		for (String key : config.getKeys(false)) {
			String problem = add(key, config.getConfigurationSection(key), vehicles);
			if (problem == null) {
				loaded++;
			} else {
				skipped++;
				VFLogger.log("Skipping player skin '" + key + "': " + problem);
			}
		}
		return new Result(loaded, skipped, false);
	}

	private static String add(String key, ConfigurationSection section, Map<String, Vehicle> vehicles) {
		if (section == null) return "not a section";
		if (!ID.matcher(key).matches()) return "id must be lowercase letters, digits and _";
		String vehicleId = section.getString("vehicle");
		Vehicle vehicle = vehicleId == null ? null : vehicles.get(vehicleId);
		if (vehicle == null) return "unknown vehicle '" + vehicleId + "'";
		String model = section.getString("model");
		if (model == null || model.isBlank()) return "no model";
		UUID owner;
		try {
			owner = UUID.fromString(section.getString("owner", "").trim());
		} catch (IllegalArgumentException e) {
			return "owner must be the uploader's UUID (player skins are owner-only)";
		}
		VehicleSkin skin = new VehicleSkin(key, section.getString("name"), model.trim(), owner);
		if (!vehicle.getSkinHandler().addPlayerSkin(skin)) {
			return "vehicle '" + vehicleId + "' already has a skin with this id";
		}
		return null;
	}
}
