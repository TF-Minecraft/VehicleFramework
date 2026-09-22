package net.tfminecraft.vehicleframework.data;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.configuration.ConfigurationSection;

import net.tfminecraft.vehicleframework.enums.VehicleDeath;

public class DeathOverride {
	private List<String> conditions = new ArrayList<>();
	private VehicleDeath death;
	
	public DeathOverride(ConfigurationSection config) {
		conditions = config.getStringList("conditions");
		death = VehicleDeath.valueOf(config.getString("type").toUpperCase());
	}

	public List<String> getConditions() {
		return conditions;
	}

	public VehicleDeath getDeath() {
		return death;
	}

}
