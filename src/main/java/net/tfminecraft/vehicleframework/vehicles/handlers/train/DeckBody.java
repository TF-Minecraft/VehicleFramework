package net.tfminecraft.vehicleframework.vehicles.handlers.train;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Shulker;
import org.bukkit.persistence.PersistentDataType;

import io.papermc.paper.entity.TeleportFlag;
import net.tfminecraft.vehicleframework.VFLogger;
import net.tfminecraft.vehicleframework.VehicleFramework;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;

/**
 * The solid boxes of one car's {@link Deck}. Each is an invisible shulker, which players
 * can stand on, riding an invisible display. Shulkers on their own snap to the block grid,
 * but a riding one sits wherever its display is.
 */
public final class DeckBody {
	private static final Map<UUID, ActiveVehicle> PARTS = new ConcurrentHashMap<>();
	private static NamespacedKey key;
	private final ActiveVehicle v;
	private final Deck deck;
	private final List<ItemDisplay> carriers = new ArrayList<>();
	private final List<Shulker> boxes = new ArrayList<>();
	// Ticks to wait before trying again after the boxes could not be spawned.
	private static final int RETRY_TICKS = 100;
	// Where the car is now, and where it was when riders were last carried. Both stay
	// null while the car has no boxes, so nobody is carried on a deck that is not there.
	private Deck.Frame frame;
	private Deck.Frame carried;
	private int retryAt;
	private boolean warned;

	public DeckBody(ActiveVehicle v, Deck deck) {
		this.v = v;
		this.deck = deck;
	}

	public Deck getDeck() {
		return deck;
	}

	public ActiveVehicle getVehicle() {
		return v;
	}

	/** The car a deck box belongs to, or null if the entity is not part of a deck. */
	public static ActiveVehicle owner(Entity entity) {
		return entity == null ? null : PARTS.get(entity.getUniqueId());
	}

	/** Whether an entity is one of the boxes or displays that make up a deck. */
	public static boolean isPart(Entity entity) {
		if (entity == null) {
			return false;
		}
		if (PARTS.containsKey(entity.getUniqueId())) {
			return true;
		}
		NamespacedKey k = key();
		return k != null && entity.getPersistentDataContainer().has(k, PersistentDataType.STRING);
	}

	/** Moves the deck to the car's new place, making its boxes the first time. */
	public void place(Deck.Frame next) {
		if (next == null || v.getEntity() == null || v.getEntity().getWorld() == null) {
			return;
		}
		if (Bukkit.getCurrentTick() < retryAt) {
			forget();
			return;
		}
		World world = v.getEntity().getWorld();
		List<Deck.Box> wanted = deck.boxes(next);
		if (boxes.size() != wanted.size() || !intact()) {
			build(world, wanted, next);
			return;
		}
		if (next.same(frame)) {
			return;
		}
		for (int i = 0; i < wanted.size(); i++) {
			Deck.Box box = wanted.get(i);
			ItemDisplay carrier = carriers.get(i);
			if (!carrier.getWorld().equals(world) || !loaded(world, box)) {
				build(world, wanted, next);
				return;
			}
			carrier.teleport(location(world, box), TeleportFlag.EntityState.RETAIN_PASSENGERS);
		}
		arrive(next);
	}

	private void build(World world, List<Deck.Box> wanted, Deck.Frame next) {
		if (rebuild(world, wanted)) {
			arrive(next);
		} else {
			forget();
		}
	}

	// The boxes are in place for this frame.
	private void arrive(Deck.Frame next) {
		if (carried == null) {
			carried = next;
		}
		frame = next;
	}

	private void forget() {
		frame = null;
		carried = null;
	}

	/** The car's frame now, or null before the deck is first placed. */
	public Deck.Frame frame() {
		return frame;
	}

	/** The car's frame when riders were last carried, or null before the deck is placed. */
	public Deck.Frame carried() {
		return carried;
	}

	/** Riders have been carried up to the car's current frame. */
	public void settle() {
		carried = frame;
	}

	public void remove() {
		despawn();
		forget();
	}

	private void despawn() {
		for (Shulker box : boxes) {
			PARTS.remove(box.getUniqueId());
			if (box.isValid()) {
				box.remove();
			}
		}
		for (ItemDisplay carrier : carriers) {
			PARTS.remove(carrier.getUniqueId());
			if (carrier.isValid()) {
				carrier.remove();
			}
		}
		boxes.clear();
		carriers.clear();
	}

	private boolean intact() {
		for (int i = 0; i < boxes.size(); i++) {
			Shulker box = boxes.get(i);
			ItemDisplay carrier = carriers.get(i);
			if (!box.isValid() || !carrier.isValid() || box.getVehicle() != carrier) {
				return false;
			}
		}
		return true;
	}

	// Whether the boxes were made for the car in this place.
	private boolean rebuild(World world, List<Deck.Box> wanted) {
		despawn();
		for (Deck.Box box : wanted) {
			if (!loaded(world, box)) {
				// Part of the car is over an unloaded chunk; try again next tick.
				return false;
			}
		}
		try {
			for (Deck.Box box : wanted) {
				spawn(world, box);
			}
			return true;
		} catch (RuntimeException e) {
			// A protection plugin can refuse the spawn. Wait a while before trying again.
			retryAt = Bukkit.getCurrentTick() + RETRY_TICKS;
			despawn();
			if (!warned) {
				warned = true;
				VFLogger.log("Could not make the walkable deck of " + v.getName() + ", retrying every "
						+ RETRY_TICKS / 20 + " s: " + e);
			}
			return false;
		}
	}

	private void spawn(World world, Deck.Box box) {
		Location at = location(world, box);
		String owner = v.getUUID();
		NamespacedKey k = key();
		ItemDisplay carrier = world.spawn(at, ItemDisplay.class, display -> {
			display.setPersistent(false);
			// Nothing shows, so move straight to each new place rather than easing into it.
			display.setTeleportDuration(0);
			if (k != null) {
				display.getPersistentDataContainer().set(k, PersistentDataType.STRING, owner);
			}
		});
		Shulker shulker = world.spawn(at, Shulker.class, s -> {
			s.setPersistent(false);
			s.setRemoveWhenFarAway(false);
			s.setAI(false);
			s.setInvisible(true);
			s.setSilent(true);
			s.setInvulnerable(true);
			s.setGravity(false);
			s.setPeek(0);
			s.setAttachedFace(BlockFace.DOWN);
			AttributeInstance scale = s.getAttribute(Attribute.SCALE);
			if (scale != null) {
				scale.setBaseValue(box.size());
			}
			if (k != null) {
				s.getPersistentDataContainer().set(k, PersistentDataType.STRING, owner);
			}
		});
		if (!carrier.isValid() || !shulker.isValid()) {
			carrier.remove();
			shulker.remove();
			throw new IllegalStateException("deck box spawn was cancelled");
		}
		carrier.addPassenger(shulker);
		carriers.add(carrier);
		boxes.add(shulker);
		PARTS.put(carrier.getUniqueId(), v);
		PARTS.put(shulker.getUniqueId(), v);
	}

	private static boolean loaded(World world, Deck.Box box) {
		return world.isChunkLoaded((int) Math.floor(box.x()) >> 4, (int) Math.floor(box.z()) >> 4);
	}

	private static Location location(World world, Deck.Box box) {
		return new Location(world, box.x(), box.y(), box.z());
	}

	private static NamespacedKey key() {
		if (key == null && VehicleFramework.plugin != null) {
			key = new NamespacedKey(VehicleFramework.plugin, "train_deck");
		}
		return key;
	}
}
