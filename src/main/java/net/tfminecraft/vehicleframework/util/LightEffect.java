package net.tfminecraft.vehicleframework.util;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.scheduler.BukkitRunnable;

import net.tfminecraft.vehicleframework.VehicleFramework;
import net.tfminecraft.vehicleframework.cache.Cache;

public class LightEffect {

    public void createTemporaryLight(Location loc, int lightLevel) {
    	// Reading or lighting a block in an unloaded chunk would load it on the main thread. Headlights
    	// stay on after the driver leaves, so a parked vehicle at the edge of the loaded area would do
    	// that every tick; nobody can see that chunk, so it simply gets no light.
    	if(loc.getWorld() == null || !loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) return;
    	if(!loc.getBlock().getType().equals(Material.AIR)) return;
    	if(Cache.lightLocations.contains(loc)) return;
    	Cache.lightLocations.add(loc);
		Block b = loc.getBlock();
		b.setType(Material.LIGHT);
		final Levelled level = (Levelled) b.getBlockData();
		level.setLevel(lightLevel);
		b.setBlockData(level, true);
		new BukkitRunnable()
		{
			public void run() {
				if (b.getType() == Material.LIGHT) b.setType(Material.AIR);
				Cache.lightLocations.remove(loc);
			}
		}.runTaskLater(VehicleFramework.plugin, 2L);
    }
}
