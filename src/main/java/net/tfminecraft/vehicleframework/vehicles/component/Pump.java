package net.tfminecraft.vehicleframework.vehicles.component;

import org.bukkit.configuration.ConfigurationSection;

import com.ticxo.modelengine.api.model.ActiveModel;

import net.tfminecraft.vehicleframework.database.IncompleteComponent;
import net.tfminecraft.vehicleframework.enums.Component;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;

public class Pump extends VehicleComponent{
	private int power;
	
	public Pump(ConfigurationSection config) {
		super(Component.PUMP, config);
		power = config.getInt("power");
	}
	
	public Pump(Pump another, ActiveVehicle v, ActiveModel m, IncompleteComponent ic) {
		super(another, v, m, ic);
		power = another.getPower();
	}
	
	public int getBasePower() {
		return power;
	}
	
	public int getPower() {
		return (int) Math.round(power*(healthData.getHealthPercentage()/100.0));
	}
}
