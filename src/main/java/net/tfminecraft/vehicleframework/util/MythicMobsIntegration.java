package net.tfminecraft.vehicleframework.util;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import io.lumine.mythic.bukkit.events.MythicMobDespawnEvent;
import net.tfminecraft.vehicleframework.VehicleFramework;
import net.tfminecraft.vehicleframework.managers.VehicleManager;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;

public class MythicMobsIntegration implements Listener{
    VehicleManager manager = VehicleFramework.getVehicleManager();

    @EventHandler
	public void despawnEvent(MythicMobDespawnEvent e) {
		ActiveVehicle v = manager.get(e.getEntity());
		if(manager.get(e.getEntity()) != null) {
			manager.unload(v);
		}
	}
}
