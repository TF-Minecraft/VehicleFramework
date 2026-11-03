package net.tfminecraft.vehicleframework.util;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.scheduler.BukkitRunnable;

import net.tfminecraft.vehicleframework.VehicleFramework;
import net.tfminecraft.vehicleframework.cache.Cache;

public class LightEffect {

    public void createTemporaryLight(Location loc, int lightLevel) {
    	// Reading or lighting a block in an unloaded chunk would load it on the main thread, and so
    	// would the physics update the light sends to its neighbours across a chunk border. Headlights
    	// stay on after the driver leaves, so a parked vehicle at the edge of the loaded area would do
    	// that every tick; nobody can see those chunks, so the light is simply skipped there.
    	World world = loc.getWorld();
    	if(world == null || !neighboursLoaded(world, loc.getBlockX(), loc.getBlockZ())) return;
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
				// Without its neighbours' chunks, remove the light without notifying them: nothing reacts to it.
				if (b.getType() == Material.LIGHT) b.setType(Material.AIR, neighboursLoaded(world, b.getX(), b.getZ()));
				Cache.lightLocations.remove(loc);
			}
		}.runTaskLater(VehicleFramework.plugin, 2L);
    }

    /** Whether the chunks of the block and all its horizontal neighbours are loaded (checking never loads one). */
    static boolean neighboursLoaded(World world, int x, int z) {
    	for (int cx = (x - 1) >> 4; cx <= (x + 1) >> 4; cx++) {
    		for (int cz = (z - 1) >> 4; cz <= (z + 1) >> 4; cz++) {
    			if (!world.isChunkLoaded(cx, cz)) return false;
    		}
    	}
    	return true;
    }
}
