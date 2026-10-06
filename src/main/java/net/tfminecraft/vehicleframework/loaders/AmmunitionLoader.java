package net.tfminecraft.vehicleframework.loaders;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import net.tfminecraft.tlibs.interfaces.LoaderInterface;
import net.tfminecraft.vehicleframework.VFLogger;
import net.tfminecraft.vehicleframework.weapons.ammunition.Ammunition;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

public class AmmunitionLoader implements LoaderInterface {

  public static HashMap<String, Ammunition> map = new HashMap<>();

  public static HashMap<String, Ammunition> get() {
    return map;
  }

  @Override
  public void load(File configFile) {
    try {
      map.putAll(read(configFile));
    } catch (IOException | InvalidConfigurationException | IllegalArgumentException e) {
      VFLogger.log("Failed to load ammunition from " + configFile + ": " + e.getMessage());
    }
  }

  public void reload(File folder) {
    try {
      File[] files = folder.listFiles(File::isFile);
      if (files == null) throw new IOException("Cannot list " + folder);
      Map<String, Ammunition> replacement = new HashMap<>();
      for (File file : files) replacement.putAll(read(file));
      map.clear();
      map.putAll(replacement);
    } catch (IOException | InvalidConfigurationException | IllegalArgumentException e) {
      VFLogger.log(
          "Failed to reload ammunition from "
              + folder
              + "; keeping existing definitions: "
              + e.getMessage());
    }
  }

  private Map<String, Ammunition> read(File configFile)
      throws IOException, InvalidConfigurationException {
    FileConfiguration config = new YamlConfiguration();
    config.load(configFile);
    Map<String, Ammunition> loaded = new HashMap<>();
    for (String key : config.getKeys(false)) {
      if (!config.isConfigurationSection(key)) {
        throw new IllegalArgumentException("Ammunition '" + key + "' must be a section");
      }
      loaded.put(key, Ammunition.create(key, config.getConfigurationSection(key)));
    }
    return loaded;
  }

  public static Ammunition getByString(String id) {
    if (map.containsKey(id)) return map.get(id);
    return null;
  }

  public static Ammunition getByInput(String s) {
    for (Map.Entry<String, Ammunition> entry : map.entrySet()) {
      if (entry.getValue().getData().getInput().equalsIgnoreCase(s)) return entry.getValue();
    }
    return null;
  }
}
