package net.tfminecraft.vehicleframework.weapons.ammunition.data.projectile;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

public interface ProjectileModel {
	
	public Entity spawn(Location loc);
	public double getOffset();
}
