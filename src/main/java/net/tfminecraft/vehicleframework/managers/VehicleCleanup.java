package net.tfminecraft.vehicleframework.managers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;

import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.data.OwnerData;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;

/**
 * Finds idle vehicles to remove on the worlds in {@code cleanup.worlds} (config.yml), such as a
 * tutorial server's world. A vehicle is due once it has had no passenger and has not moved for
 * {@code cleanup.idle-minutes}, counted from when the cleanup first saw it loaded or last saw it in
 * use. Moving counts as use, so a train car behind an occupied locomotive, a horse-drawn cart or a
 * towed vehicle is not idle. Owners in {@code cleanup.keep-owners} (stored owner values, e.g.
 * {@code char_<uuid>}) are never removed. With no worlds configured nothing is ever due.
 */
public final class VehicleCleanup {

	private final Map<String, Long> lastActive = new HashMap<>();
	private final Map<String, double[]> lastPosition = new HashMap<>();

	/** The loaded vehicles to remove now; also forgets vehicles that are no longer loaded. */
	public List<ActiveVehicle> due(Collection<ActiveVehicle> vehicles, long now) {
		List<ActiveVehicle> due = new ArrayList<>();
		if (Cache.cleanupWorlds.isEmpty()) {
			lastActive.clear();
			lastPosition.clear();
			return due;
		}
		Set<String> seen = new HashSet<>();
		for (ActiveVehicle v : vehicles) {
			String id = v.getUUID();
			// One vehicle can be registered under several entities: judge it once.
			if (id == null || v.isDestroyed() || !seen.add(id) || !onCleanupWorld(v) || isKept(v)) {
				continue;
			}
			boolean moved = moved(id, v.getEntity().getLocation());
			if (moved || !v.getSeatHandler().getPassengers().isEmpty()) {
				lastActive.put(id, now);
				continue;
			}
			Long since = lastActive.putIfAbsent(id, now);
			if (since != null && now - since >= Cache.cleanupIdleMillis) {
				due.add(v);
			}
		}
		lastActive.keySet().retainAll(seen);
		lastPosition.keySet().retainAll(seen);
		return due;
	}

	/** Whether the vehicle is more than a block from where the last check saw it. */
	private boolean moved(String id, Location at) {
		if (at == null) {
			return false;
		}
		double[] now = {at.getX(), at.getY(), at.getZ()};
		double[] before = lastPosition.put(id, now);
		return before != null
				&& Math.abs(now[0] - before[0]) + Math.abs(now[1] - before[1]) + Math.abs(now[2] - before[2]) > 1.0;
	}

	private static boolean onCleanupWorld(ActiveVehicle v) {
		Entity entity = v.getEntity();
		World world = entity == null ? null : entity.getWorld();
		return world != null && Cache.cleanupWorlds.contains(world.getName().toLowerCase(Locale.ROOT));
	}

	private static boolean isKept(ActiveVehicle v) {
		OwnerData owner = v.getOwnerData();
		return owner != null && owner.getOwner() != null && Cache.cleanupKeepOwners.contains(owner.getOwner());
	}
}
