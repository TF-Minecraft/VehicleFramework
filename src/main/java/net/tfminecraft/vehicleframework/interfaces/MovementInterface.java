package net.tfminecraft.vehicleframework.interfaces;

import org.bukkit.entity.Player;

import net.tfminecraft.vehicleframework.enums.Input;

public interface MovementInterface {
	public void input(Player p, Input i);
}
