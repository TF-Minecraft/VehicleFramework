package net.tfminecraft.vehicleframework.loaders;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tlibs.interfaces.LoaderInterface;
import net.tfminecraft.vehicleframework.weapons.ammunition.Ammunition;

public class AmmunitionLoader implements LoaderInterface{

	public static HashMap<String, Ammunition> map = new HashMap<>();
	
	public static HashMap<String, Ammunition> get(){
		return map;
	}
	
	@Override
	public void load(File configFile) {
		FileConfiguration config = new YamlConfiguration();
        try {
        	config.load(configFile);
        } catch (IOException | InvalidConfigurationException e) {
            e.printStackTrace();
        }
        Set<String> set = config.getKeys(false);

		List<String> list = new ArrayList<String>(set);
		
		for(String key : list) {
			Ammunition o = Ammunition.create(key, config.getConfigurationSection(key));
			map.put(key, o);
		}
	}

	public static Ammunition getByString(String id) {
		if(map.containsKey(id)) return map.get(id);
		return null;
	}
	
	public static Ammunition getByInput(String s) {
		for(Map.Entry<String, Ammunition> entry : map.entrySet()) {
			if(entry.getValue().getData().getInput().equalsIgnoreCase(s)) return entry.getValue();
		}
		return null;
	}

}
