package net.tfminecraft.vehicleframework.weapons.ammunition;

import org.bukkit.configuration.ConfigurationSection;

import net.tfminecraft.vehicleframework.VFLogger;
import net.tfminecraft.vehicleframework.enums.Projectile;
import net.tfminecraft.vehicleframework.weapons.ammunition.data.AmmunitionData;

public class Ammunition {
	protected Projectile type;
	protected String name;
	protected String id;
	protected AmmunitionData data;
	
	public static Ammunition create(String key, ConfigurationSection config) {
		String t = config.getString("type", "CANNONBALL");
		Projectile type = Projectile.valueOf(t.toUpperCase(java.util.Locale.ROOT));
		return switch (type) {
			case BULLET -> new Bullet(key, config);
			case CANNONBALL -> new Ammunition(key, config);
			case CLUSTER -> new ClusterBomb(key, config);
			case TORPEDO, BOMB -> new FusedExplosive(key, config);
		};
	}
	
	public Ammunition (String key, ConfigurationSection config) {
		id = key;
		name = config.getString("name", key);
		data = new AmmunitionData(config);
		String t = config.getString("type", "CANNONBALL");
		type = Projectile.valueOf(t.toUpperCase(java.util.Locale.ROOT));
	}
	
	public Projectile getType() {
		return type;
	}

	public String getId() {
		return id;
	}
	
	public String getName() {
		return name;
	}
	
	public AmmunitionData getData() {
		return data;
	}
}
