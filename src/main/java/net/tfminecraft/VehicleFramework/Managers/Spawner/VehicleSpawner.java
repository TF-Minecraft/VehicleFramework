package net.tfminecraft.VehicleFramework.Managers.Spawner;

import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;

import com.ticxo.modelengine.api.ModelEngineAPI;
import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.ModeledEntity;

import io.lumine.mythic.api.mobs.MythicMob;
import io.lumine.mythic.bukkit.BukkitAdapter;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.core.mobs.ActiveMob;
import net.tfminecraft.VehicleFramework.VFLogger;
import net.tfminecraft.VehicleFramework.Cache.Cache;
import net.tfminecraft.VehicleFramework.Database.IncompleteVehicle;
import net.tfminecraft.VehicleFramework.Managers.VehicleManager;
import net.tfminecraft.VehicleFramework.Vehicles.ActiveVehicle;
import net.tfminecraft.VehicleFramework.Vehicles.Vehicle;
import net.tfminecraft.VehicleFramework.Vehicles.Handlers.Skins.VehicleSkin;
import net.tfminecraft.VehicleFramework.Vehicles.Handlers.SkinHandler;
import net.tfminecraft.VehicleFramework.Util.VehicleEntityCleanup;

public class VehicleSpawner {

	public ActiveVehicle spawn(Location loc, Vehicle v, VehicleManager manager, IncompleteVehicle i) {
		Entity e = null;
		try {
			String skinId = i == null ? v.getSkinHandler().getCurrentSkin().getId() : i.getSkin();
			VehicleSkin skin = v.getSkinHandler().getSkins().get(skinId);
			if (skin == null || !SkinHandler.isModelAvailable(skin)) {
				throw new IllegalArgumentException("Missing vehicle skin/model: " + skinId);
			}
			if(!Cache.mythicMob.equalsIgnoreCase("none")) {
				MythicMob mob = MythicBukkit.inst().getMobManager().getMythicMob(Cache.mythicMob).orElse(null);
				if(mob != null){
					ActiveMob activeMob = mob.spawn(BukkitAdapter.adapt(loc),1);
					e = activeMob.getEntity().getBukkitEntity();
				} else {
					VFLogger.log(" could not find the " + Cache.mythicMob + " mythicmob");
					return null;
				}
			} else {
				ArmorStand a = loc.getWorld().spawn(loc, ArmorStand.class);
				a.setVisible(false);
				e = a;
			}

			// SQLite owns persistence; never let Bukkit/ME independently restore this runtime entity.
			e.setPersistent(false);
			ModeledEntity modeledEntity = ModelEngineAPI.createModeledEntity(e);
			modeledEntity.setSaved(false);
			ActiveModel m = ModelEngineAPI.createActiveModel(skin.getModel());
			modeledEntity.addModel(m, true);
			m.getMountManager().get().setCanRide(true);
			return new ActiveVehicle(v, e, m, manager, i);
		} catch (RuntimeException ex) {
			if (e != null) {
				try {
					VehicleEntityCleanup.remove(e);
				} catch (RuntimeException cleanup) {
					ex.addSuppressed(cleanup);
				}
			}
			VFLogger.log("Failed to spawn " + v.getId() + ": " + ex);
			return null;
		}
	}
}
