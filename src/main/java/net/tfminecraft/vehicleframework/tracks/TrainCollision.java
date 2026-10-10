package net.tfminecraft.vehicleframework.tracks;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.database.PersistenceLog;
import net.tfminecraft.vehicleframework.enums.VehicleDeath;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;

/**
 * Trains that touch on the same track. Closing faster than {@code collision.explode-speed},
 * the locomotive and what it hits explode; slower, whichever is moving into the other stops.
 */
public final class TrainCollision {
	// A driver who keeps nudging into a train is told about it at most this often.
	static final int TELL_TICKS = 100;
	static final String BUMP_SOUND = "minecraft:block.anvil.land";

	// Where each train car stood last tick, to tell which way it is moving.
	private static final Map<String, Vector> LAST = new HashMap<>();
	private static final Map<String, Long> TOLD = new HashMap<>();
	private static long tick;

	private TrainCollision() {
	}

	public static void tick(Collection<ActiveVehicle> vehicles) {
		tick++;
		if (vehicles == null || vehicles.isEmpty()) {
			LAST.clear();
			return;
		}
		List<ActiveVehicle> list = new ArrayList<>(vehicles);
		Map<String, Vector> velocities = velocities(list);
		Set<String> exploding = new HashSet<>();
		for (ActiveVehicle loco : list) {
			if (!isBoundHead(loco) || exploding.contains(key(loco))) {
				continue;
			}
			for (ActiveVehicle other : list) {
				if (other == loco || exploding.contains(key(other))) {
					continue;
				}
				if (!other.isTrain() || other.isDestroyed()) {
					continue;
				}
				if (isBoundHead(other) && key(loco).compareToIgnoreCase(key(other)) >= 0) {
					continue;
				}
				if (!sameSpline(loco, other) || sameConsist(loco, other)) {
					continue;
				}
				if (!overlaps(loco, other)) {
					continue;
				}
				Vector at = position(loco);
				Vector otherAt = position(other);
				Vector velocity = velocities.getOrDefault(key(loco), new Vector());
				Vector otherVelocity = velocities.getOrDefault(key(other), new Vector());
				if (closingSpeed(at, velocity, otherAt, otherVelocity) * 20 >= Cache.trainCollisionExplodeSpeed) {
					explodePair(loco, other);
					exploding.add(key(loco));
					exploding.add(key(other));
					break;
				}
				if (towards(velocity, at, otherAt)) {
					bump(loco);
				}
				if (towards(otherVelocity, otherAt, at)) {
					bump(other);
				}
			}
		}
	}

	/** How fast two trains close, in the units of their velocities; negative while they part. */
	static double closingSpeed(Vector at, Vector velocity, Vector otherAt, Vector otherVelocity) {
		Vector apart = otherAt.clone().subtract(at);
		Vector relative = velocity.clone().subtract(otherVelocity);
		double distance = apart.length();
		return distance < 1e-9 ? relative.length() : relative.dot(apart) / distance;
	}

	static boolean towards(Vector velocity, Vector from, Vector to) {
		return velocity.dot(to.clone().subtract(from)) > 0;
	}

	/**
	 * Each train car's velocity in blocks a tick: the way it moved since last tick, at its
	 * train's speed. The speed comes from the throttle, so a car placed or re-found on the
	 * track does not count as moving fast.
	 */
	private static Map<String, Vector> velocities(List<ActiveVehicle> list) {
		Map<String, Vector> now = new HashMap<>();
		Map<String, Vector> velocities = new HashMap<>();
		for (ActiveVehicle v : list) {
			if (!v.isTrain() || v.isDestroyed() || v.getEntity() == null) {
				continue;
			}
			Vector at = v.getEntity().getLocation().toVector();
			now.put(key(v), at);
			Vector last = LAST.get(key(v));
			double speed = speed(head(v));
			Vector moved = last == null ? new Vector() : at.clone().subtract(last);
			velocities.put(key(v), speed == 0 || moved.lengthSquared() < TrackSplineMotion.MOVE_EPS_SQ
					? new Vector() : moved.normalize().multiply(speed));
		}
		LAST.clear();
		LAST.putAll(now);
		return velocities;
	}

	private static double speed(ActiveVehicle v) {
		double raw = v.getAccessPanel() == null ? 0 : v.getAccessPanel().getSpeed();
		return TrackSplineMotion.stopped(raw) ? 0 : Math.abs(raw);
	}

	/** Stops the train this car belongs to, as running into the end of the track does. */
	private static void bump(ActiveVehicle car) {
		ActiveVehicle head = head(car);
		if (head.getThrottle() != null) {
			head.getThrottle().setThrottle(0);
		}
		if (head.getAccessPanel() != null) {
			head.getAccessPanel().setSpeed(0);
		}
		Long told = TOLD.get(key(head));
		if (told != null && tick - told < TELL_TICKS) {
			return;
		}
		TOLD.put(key(head), tick);
		PersistenceLog.append("BUMP " + head.getUUID() + " car=" + car.getUUID());
		Entity entity = car.getEntity();
		entity.getWorld().playSound(entity.getLocation(), BUMP_SOUND, 0.6f, 0.6f);
		Player captain = head.getSeatHandler() == null ? null : head.getSeatHandler().captainPlayer();
		if (captain != null) {
			captain.sendMessage("§eYour train bumped into another and stopped.");
		}
	}

	private static ActiveVehicle head(ActiveVehicle v) {
		ActiveVehicle cur = v;
		int guard = 0;
		while (cur.hasParent() && guard++ < 64) {
			cur = cur.getParent();
		}
		return cur;
	}

	private static Vector position(ActiveVehicle v) {
		return v.getEntity().getLocation().toVector();
	}

	public static boolean sameConsist(ActiveVehicle a, ActiveVehicle b) {
		if (a == null || b == null) {
			return false;
		}
		String ha = consistKey(a);
		String hb = consistKey(b);
		return ha != null && ha.equalsIgnoreCase(hb);
	}

	public static String consistKey(ActiveVehicle v) {
		if (v == null) {
			return null;
		}
		ActiveVehicle cur = head(v);
		if (cur.isTrain()) {
			String pending = cur.getTrainHandler().getPendingParent();
			if (pending != null && !pending.isBlank()) {
				return pending;
			}
		}
		return cur.getUUID();
	}

	static String consistHead(String uuid, String liveParent, String pendingParent) {
		if (liveParent != null && !liveParent.isBlank()) {
			return liveParent;
		}
		if (pendingParent != null && !pendingParent.isBlank()) {
			return pendingParent;
		}
		return uuid;
	}

	static boolean sameConsistIds(
			String aUuid, String aLiveParent, String aPendingParent,
			String bUuid, String bLiveParent, String bPendingParent) {
		String a = consistHead(aUuid, aLiveParent, aPendingParent);
		String b = consistHead(bUuid, bLiveParent, bPendingParent);
		return a != null && a.equalsIgnoreCase(b);
	}

	private static boolean isBoundHead(ActiveVehicle v) {
		return v != null
				&& v.isTrain()
				&& !v.isDestroyed()
				&& !v.hasParent()
				&& v.getTrainHandler().isBound()
				&& v.getEntity() != null
				&& !v.getEntity().isDead();
	}

	private static boolean sameSpline(ActiveVehicle a, ActiveVehicle b) {
		UUID sa = a.getTrainHandler().getSplineId();
		UUID sb = b.getTrainHandler().getSplineId();
		return sa != null && sa.equals(sb);
	}

	private static boolean overlaps(ActiveVehicle a, ActiveVehicle b) {
		Entity ea = a.getEntity();
		Entity eb = b.getEntity();
		if (ea == null || eb == null || ea.getWorld() == null || eb.getWorld() == null) {
			return false;
		}
		if (!ea.getWorld().equals(eb.getWorld())) {
			return false;
		}
		BoundingBox ba = ea.getBoundingBox();
		BoundingBox bb = eb.getBoundingBox();
		return ba != null && bb != null && ba.overlaps(bb);
	}

	private static void explodePair(ActiveVehicle a, ActiveVehicle b) {
		PersistenceLog.append("COLLIDE a=" + a.getUUID()
				+ " b=" + b.getUUID()
				+ " spline=" + a.getTrainHandler().getSplineId()
				+ " sA=" + a.getTrainHandler().getS()
				+ " sB=" + b.getTrainHandler().getS());
		explode(a);
		explode(b);
	}

	private static void explode(ActiveVehicle v) {
		if (v == null || v.isDestroyed()) {
			return;
		}
		if (!v.hasDeathData(VehicleDeath.EXPLODE)) {
			return;
		}
		v.kill(VehicleDeath.EXPLODE);
	}

	private static String key(ActiveVehicle v) {
		return v.getUUID() == null ? "" : v.getUUID();
	}
}
