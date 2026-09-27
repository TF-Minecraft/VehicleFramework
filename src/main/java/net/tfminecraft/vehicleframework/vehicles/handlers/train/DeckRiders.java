package net.tfminecraft.vehicleframework.vehicles.handlers.train;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.util.RelativeMove;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;

/**
 * Moves players standing on a train's deck along with it, once every car has been placed
 * for the tick. Minecraft does not carry players on moving entities, so each is shifted
 * by as much as the patch of deck under them moved, and turned as the car turns.
 */
public final class DeckRiders {
	// Shifts older than this are long confirmed, even on a very laggy connection.
	private static final int KEEP_TICKS = 40;
	// Players this far from a car's origin cannot be on its deck.
	private static final double REACH = 16;
	// Feet no further than a step below a box top are lifted onto it.
	private static final double STEP = 0.6;
	private static final Map<UUID, Deque<Shift>> SENT = new HashMap<>();
	private static long tick;

	private record Shift(long tick, double dx, double dy, double dz) {
	}

	private DeckRiders() {
	}

	public static void tick(Collection<ActiveVehicle> vehicles) {
		tick++;
		Set<UUID> carried = new HashSet<>();
		for (ActiveVehicle v : vehicles) {
			if (!v.isTrain() || v.getEntity() == null || v.getEntity().getWorld() == null) {
				continue;
			}
			DeckBody body = v.getTrainHandler().getDeckBody();
			if (body == null) {
				continue;
			}
			Deck.Frame from = body.carried();
			Deck.Frame to = body.frame();
			body.settle();
			if (from == null || to == null || from.same(to)) {
				continue;
			}
			double max = Cache.trainDeckCarryMaxSpeed;
			if (max <= 0 || from.distance(to) > max) {
				// Too fast to hold on: the deck slides out from under them.
				continue;
			}
			Location centre = v.getEntity().getLocation();
			for (Player p : centre.getWorld().getNearbyPlayers(centre, REACH)) {
				if (carried.contains(p.getUniqueId()) || !canRide(p)) {
					continue;
				}
				double[] at = clientPosition(p);
				Location seen = p.getLocation();
				// The estimate can count a shift the server has already seen, so a rider near
				// an edge counts as on the deck if either their estimate or last report is on it.
				if (!body.getDeck().carries(from, at[0], at[1], at[2])
						&& !body.getDeck().carries(from, seen.getX(), seen.getY(), seen.getZ())) {
					continue;
				}
				double[] next = Deck.carry(from, to, at[0], at[1], at[2]);
				double floor = body.getDeck().support(from, to, next[0], next[2]);
				if (next[1] < floor && next[1] > floor - STEP) {
					// Lift feet out of a box they would sink into, or they fall through it.
					next[1] = floor;
				}
				double dx = next[0] - at[0];
				double dy = next[1] - at[1];
				double dz = next[2] - at[2];
				if (RelativeMove.send(p, dx, dy, dz, Deck.turn(from, to))) {
					carried.add(p.getUniqueId());
					SENT.computeIfAbsent(p.getUniqueId(), id -> new ArrayDeque<>())
							.addLast(new Shift(tick, dx, dy, dz));
				}
			}
		}
		forgetOld();
	}

	private static boolean canRide(Player p) {
		return p.isOnline() && !p.isDead() && !p.isInsideVehicle() && !p.isFlying() && !p.isGliding()
				&& !p.isSleeping() && p.getGameMode() != GameMode.SPECTATOR;
	}

	/**
	 * Where the player is on their own client. The server only knows where the client last
	 * said it was, which lags behind the shifts sent to it since, so add those back.
	 */
	private static double[] clientPosition(Player p) {
		Location loc = p.getLocation();
		double[] at = { loc.getX(), loc.getY(), loc.getZ() };
		Deque<Shift> sent = SENT.get(p.getUniqueId());
		if (sent == null) {
			return at;
		}
		// A shift reaches the client and comes back in its movement a round trip later.
		long unconfirmed = Math.round(Math.max(0, p.getPing()) / 50.0) + 1;
		for (Shift shift : sent) {
			if (tick - shift.tick() <= unconfirmed) {
				at[0] += shift.dx();
				at[1] += shift.dy();
				at[2] += shift.dz();
			}
		}
		return at;
	}

	private static void forgetOld() {
		Iterator<Deque<Shift>> players = SENT.values().iterator();
		while (players.hasNext()) {
			Deque<Shift> sent = players.next();
			while (!sent.isEmpty() && tick - sent.peekFirst().tick() > KEEP_TICKS) {
				sent.pollFirst();
			}
			if (sent.isEmpty()) {
				players.remove();
			}
		}
	}
}
