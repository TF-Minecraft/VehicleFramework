package net.tfminecraft.vehicleframework.interfaces;

import java.util.List;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import net.tfminecraft.vehicleframework.weapons.ActiveWeapon;
import net.tfminecraft.vehicleframework.weapons.ammunition.Ammunition;

public interface Shooter {
	public void shoot(List<Player> players, Entity e, Location loc, Vector vector, Ammunition a, ActiveWeapon w);
}
