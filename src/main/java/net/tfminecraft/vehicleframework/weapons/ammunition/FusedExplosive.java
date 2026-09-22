package net.tfminecraft.vehicleframework.weapons.ammunition;

import org.bukkit.configuration.ConfigurationSection;

public class FusedExplosive extends Ammunition{
	private int fuse;
	
	public FusedExplosive (String key, ConfigurationSection config) {
		super(key, config);
		fuse = config.getInt("fuse", 60);
	}
	
	public int getFuse() {
		return fuse;
	}
}
