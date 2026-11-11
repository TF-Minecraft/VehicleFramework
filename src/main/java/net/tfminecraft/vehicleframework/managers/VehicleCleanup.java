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
 * {@code cleanup.idle-minutes}, counted from when the cleanup first saw it loaded or it was last in
 * use. Use is a passenger at a check, a mount or dismount at any time ({@link #markUsed}), or moving
 * more than a block between checks, so a train car behind an occupied locomotive, a horse-drawn cart
 * or a towed vehicle is not idle. A vehicle that unloads and loads again starts a fresh clock.
 * Owners in {@code cleanup.keep-owners} (stored owner values, e.g. {@code char_<uuid>}) are never
 * removed. With no worlds configured nothing is ever due.
 */
public final class VehicleCleanup {

	/** One loaded vehicle: the instance, when it was last in use and where the last check saw it. */
	private static final class Track {
		final ActiveVehicle vehicle;
		long since;
		double[] position;

		Track(ActiveVehicle vehicle, long since) {
			this.vehicle = vehicle;
			this.since = since;
		}
	}

	private final Map<String, Track> tracks = new HashMap<>();

	/** Someone got in or out: the vehicle's idle clock starts again now. */
	public void markUsed(ActiveVehicle v, long now) {
		Track track = v == null || v.getUUID() == null ? null : tracks.get(v.getUUID());
		if (track != null && track.vehicle == v) {
			track.since = now;
		}
	}

	/** The loaded vehicles to remove now; also forgets vehicles that are no longer loaded. */
	public List<ActiveVehicle> due(Collection<ActiveVehicle> vehicles, long now) {
		List<ActiveVehicle> due = new ArrayList<>();
		if (Cache.cleanupWorlds.isEmpty()) {
			tracks.clear();
			return due;
		}
		Set<String> seen = new HashSet<>();
		for (ActiveVehicle v : vehicles) {
			String id = v.getUUID();
			// One vehicle can be registered under several entities: judge it once.
			if (id == null || v.isDestroyed() || !seen.add(id) || !onCleanupWorld(v) || isKept(v)) {
				continue;
			}
			Track track = tracks.get(id);
			if (track == null || track.vehicle != v) {
				// first sight, or loaded again since the last check: a fresh clock
				track = new Track(v, now);
				tracks.put(id, track);
				moved(track, v.getEntity().getLocation());
				continue;
			}
			if (moved(track, v.getEntity().getLocation()) || !v.getSeatHandler().getPassengers().isEmpty()) {
				track.since = now;
			} else if (now - track.since >= Cache.cleanupIdleMillis) {
				due.add(v);
			}
		}
		tracks.keySet().retainAll(seen);
		return due;
	}

	/** Whether the vehicle is more than a block from where the last check saw it; remembers where it is. */
	private static boolean moved(Track track, Location at) {
		if (at == null) {
			return false;
		}
		double[] now = {at.getX(), at.getY(), at.getZ()};
		double[] before = track.position;
		track.position = now;
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
