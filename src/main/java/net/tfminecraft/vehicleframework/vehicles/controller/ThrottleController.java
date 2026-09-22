package net.tfminecraft.vehicleframework.vehicles.controller;

import org.bukkit.entity.Player;

import net.tfminecraft.vehicleframework.enums.Component;
import net.tfminecraft.vehicleframework.enums.SeatType;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.component.Engine;
import net.tfminecraft.vehicleframework.vehicles.component.GearedEngine;
import net.tfminecraft.vehicleframework.vehicles.component.gear.Gear;
import net.tfminecraft.vehicleframework.vehicles.component.propulsion.Throttle;

public class ThrottleController {
	public void throttle(ActiveVehicle v, Player p,  boolean down) {
		if(v.getSeat(p).getType().equals(SeatType.CAPTAIN)) {
			if(v.usesFuel() && !v.hasFuel()) return;
 			if(v.hasComponent(Component.ENGINE)) {
				Engine engine = (Engine) v.getComponent(Component.ENGINE);
				if(engine.requiresStart() && !engine.isStarted()) {
					engine.start(p);
					return;
				}
				if(down) {
					engine.getThrottle().change(-1);
				} else {
					engine.getThrottle().change(1);
				}
				
			} else if(v.hasComponent(Component.GEARED_ENGINE)) {
				GearedEngine engine = (GearedEngine) v.getComponent(Component.GEARED_ENGINE);
				if(engine.requiresStart() && !engine.isStarted()) {
					engine.start(p);
					return;
				}
				engine.throttle(down);
			}	
		}
	}
}
