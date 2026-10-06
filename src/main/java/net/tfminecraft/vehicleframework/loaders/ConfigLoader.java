package net.tfminecraft.vehicleframework.loaders;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.VFLogger;

public class ConfigLoader {

	public void load(File configFile) {
		VFLogger.info("Loading config...");
		FileConfiguration config = new YamlConfiguration();
        try {
        	config.load(configFile);
        } catch (IOException | InvalidConfigurationException e) {
            e.printStackTrace();
            return;
        }
        Cache.ignoreExplode.clear();
        Cache.ignoreLands.clear();
        Cache.ignoreGround.clear();
        Cache.convertExplode.clear();
        if(config.contains("ignore-explosion")) {
        	for(String s : config.getStringList("ignore-explosion")) {
				try {
					Cache.ignoreExplode.add(Material.valueOf(s.toUpperCase(Locale.ROOT)));
				} catch (Exception e) {
					VFLogger.log(s+" is not a material");
				}
    		}
        }
        if(config.contains("ignore-landing")) {
			for(String s : config.getStringList("ignore-landing")) {
				try {
					Cache.ignoreLands.add(Material.valueOf(s.toUpperCase(Locale.ROOT)));
				} catch (Exception e) {
					VFLogger.log(s+" is not a material");
				}
			}
        }

		if(config.contains("ignore-ground")) {
			for(String s : config.getStringList("ignore-ground")) {
				try {
					Cache.ignoreGround.add(Material.valueOf(s.toUpperCase(Locale.ROOT)));
				} catch (Exception e) {
					VFLogger.log(s+" is not a material");
				}
			}
        }
        
        if(config.contains("convert-explosion")) {
			for(String s : config.getStringList("convert-explosion")) {
                try {
                    String[] parts = s.split("\\.", -1);
                    if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                        VFLogger.log("Invalid explosion conversion: " + s);
                        continue;
                    }
                    Cache.convertExplode.put(Material.valueOf(parts[0].trim().toUpperCase(Locale.ROOT)),
                            Material.valueOf(parts[1].trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    VFLogger.log("Invalid explosion conversion: " + s);
                }
			}
        }

		Cache.enableLogging = config.getBoolean("enable-logging");
		Cache.blockDamage = config.getBoolean("block-damage", true);
		
		Cache.despawnDistance = (int) Math.round(Math.pow(config.getInt("despawn-distance", 64), 2));

		Cache.skinItem = config.getString("skin-item", "v.bucket");
		Cache.repairItem = config.getString("repair-item", "v.iron_shovel");
		Cache.destroyItem = config.getString("destroy-item", "v.stone_axe");
		Cache.ticketItem = config.getString("ticket-item", "v.paper");

		Cache.mythicMob = config.getString("mythicmob", "none");
		Cache.allowWhitelist = config.getBoolean("allow-whitelist", false);
		Cache.whitelistedByDefault = config.getBoolean("whitelisted-by-default", false);
		Cache.weaponDegradedReloadMultiplier = Math.max(1.0, config.getDouble("weapon-degraded-reload-multiplier", 2.0));
		Cache.weaponAimDebug = config.getBoolean("weapon-aim-debug", false);
		Cache.terrainFollowDebug = config.getBoolean("terrain-follow-debug", false);
		Cache.groundEngineLogging = config.getBoolean("ground-engine-logging", false);
		Cache.persistenceLogging = config.getBoolean("persistence-logging", false);
		Cache.wipeLog = config.getBoolean("wipe-log", false);
	}
}
