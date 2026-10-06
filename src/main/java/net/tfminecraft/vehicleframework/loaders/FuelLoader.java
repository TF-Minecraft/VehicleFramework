package net.tfminecraft.vehicleframework.loaders;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import net.tfminecraft.tlibs.interfaces.LoaderInterface;
import net.tfminecraft.vehicleframework.VFLogger;
import net.tfminecraft.vehicleframework.vehicles.fuel.Fuel;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

public class FuelLoader implements LoaderInterface {
	public static HashMap<String, Fuel> map = new HashMap<>();

	public static HashMap<String, Fuel> get() {
		return map;
	}

	@Override
	public void load(File configFile) {
		try {
			map.putAll(read(configFile));
		} catch (IOException | InvalidConfigurationException | IllegalArgumentException e) {
			VFLogger.log("Failed to load fuel from " + configFile + ": " + e.getMessage());
		}
	}

	public void reload(File configFile) {
		try {
			Map<String, Fuel> replacement = read(configFile);
			map.clear();
			map.putAll(replacement);
		} catch (IOException | InvalidConfigurationException | IllegalArgumentException e) {
			VFLogger.log(
					"Failed to reload fuel from "
							+ configFile
							+ "; keeping existing definitions: "
							+ e.getMessage());
		}
	}

	private Map<String, Fuel> read(File configFile)
			throws IOException, InvalidConfigurationException {
		FileConfiguration config = new YamlConfiguration();
		config.load(configFile);
		Map<String, Fuel> loaded = new HashMap<>();
		for (String key : config.getKeys(false)) {
			if (!config.isConfigurationSection(key)) {
				throw new IllegalArgumentException("Fuel '" + key + "' must be a section");
			}
			loaded.put(key, new Fuel(key, config.getConfigurationSection(key)));
		}
		return loaded;
	}

	public static Fuel getByString(String id) {
		if (map.containsKey(id)) return map.get(id);
		return null;
	}

	public static Fuel getByInput(String item) {
		for (Map.Entry<String, Fuel> entry : map.entrySet()) {
			if (entry.getValue().getItem().equalsIgnoreCase(item)) return entry.getValue();
		}
		return null;
	}

	public static boolean itemIsFuel(String item) {
		for (Map.Entry<String, Fuel> entry : map.entrySet()) {
			if (entry.getValue().getItem().equalsIgnoreCase(item)) return true;
		}
		return false;
	}
}
