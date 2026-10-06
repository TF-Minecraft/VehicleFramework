package net.tfminecraft.vehicleframework.loaders;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import net.tfminecraft.tlibs.interfaces.LoaderInterface;
import net.tfminecraft.vehicleframework.VFLogger;
import net.tfminecraft.vehicleframework.vehicles.Vehicle;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

public class VehicleLoader implements LoaderInterface {
  public static HashMap<String, Vehicle> map = new HashMap<>();

  public static HashMap<String, Vehicle> get() {
    return map;
  }

  @Override
  public void load(File configFile) {
    try {
      map.putAll(read(configFile));
    } catch (IOException | InvalidConfigurationException e) {
      VFLogger.log("Failed to load vehicles from " + configFile + ": " + e.getMessage());
    }
  }

  public void reload(File folder) {
    try {
      File[] files = folder.listFiles(File::isFile);
      if (files == null) throw new IOException("Cannot list " + folder);
      Map<String, Vehicle> replacement = new HashMap<>();
      for (File file : files) replacement.putAll(read(file));
      map.clear();
      map.putAll(replacement);
    } catch (IOException | InvalidConfigurationException e) {
      VFLogger.log(
          "Failed to reload vehicles from "
              + folder
              + "; keeping existing definitions: "
              + e.getMessage());
    }
  }

  private Map<String, Vehicle> read(File configFile)
      throws IOException, InvalidConfigurationException {
    FileConfiguration config = new YamlConfiguration();
    config.load(configFile);
    Map<String, Vehicle> loaded = new HashMap<>();
    for (String key : config.getKeys(false)) {
      try {
        if (!config.isConfigurationSection(key)) {
          throw new IllegalArgumentException("Vehicle must be a section");
        }
        loaded.put(key, new Vehicle(key, config.getConfigurationSection(key)));
      } catch (RuntimeException e) {
        VFLogger.log(
            "Skipping vehicle " + key + " in " + configFile.getName() + ": " + e.getMessage());
        // A bad edit must not discard a definition already used by saved vehicles.
        if (map.containsKey(key)) loaded.put(key, map.get(key));
      }
    }
    return loaded;
  }

  public static Vehicle getByString(String id) {
    if (map.containsKey(id)) return map.get(id);
    return null;
  }
}
