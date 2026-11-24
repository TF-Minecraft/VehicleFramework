package net.tfminecraft.vehicleframework.vehicles.seat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import net.tfminecraft.vehicleframework.VFLogger;
import net.tfminecraft.vehicleframework.enums.SeatType;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.weapons.ActiveWeapon;

public class Seat {
	/**
	 * How many seats each entity holds, in every vehicle. Damage events ask whether the damaged
	 * entity rides anything, and almost none do; an entity missing here holds no seat. A seat whose
	 * vehicle is dropped without dismounting it (a removed vehicle leaves its harness seats as they
	 * are) only leaves its entry behind, which costs the full search and never a wrong answer. The
	 * keys are weak, so such an entry goes once nothing else holds the entity.
	 */
	private static final Map<Entity, Integer> SEATED = Collections.synchronizedMap(new WeakHashMap<>());
	private HashMap<Player, Long> cooldown = new HashMap<>();
	//Set when vehicle is active
	private Entity e;
	private int currentWeapon = 0;
	private List<ActiveWeapon> weapons = new ArrayList<>();
	
	private ActiveVehicle vehicle;
	private ActiveVehicle mountedVehicle;
	
	//Set by config
	private SeatType type;
	private String bone;
	
	public Seat(String s, String id) {
		int open = s.indexOf('(');
		if (open <= 0 || !s.endsWith(")") || open == s.length() - 2) {
			throw new IllegalArgumentException("Invalid seat '" + s + "' for vehicle " + id);
		}
		String seatType = s.substring(0, open).trim();
		try {
			type = SeatType.valueOf(seatType.toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException invalidType) {
			type = SeatType.PASSENGER;
		}
		bone = s.substring(open + 1, s.length() - 1);
	}
	
	public Seat(SeatType t, String bone) {
		type = t;
		this.bone = bone;
	}
	
	public Seat(ActiveVehicle v, Seat another) {
		type = another.getType();
		bone = another.getBone();
		vehicle = v;
		v.getAccessPanel().addSeat(this);
	}
	
	public boolean isOccupied() {
		return e != null;
	}

	public Entity getEntity() {
		return e;
	}
	public void mount(ActiveVehicle v, ActiveVehicle parent) {
		if (isOccupied()) return;
		mount(v.getEntity());
		mountedVehicle = v;
		v.setParent(parent);
	}
	
	/** Whether any seat holds this entity; false means no vehicle has it as a passenger. */
	public static boolean isSeatedAnywhere(Entity entity) {
		return entity != null && SEATED.containsKey(entity);
	}

	public void mount(Entity entity) {
		if(e != null) return;
		e = entity;
		if (entity != null) SEATED.merge(entity, 1, Integer::sum);
		if(hasWeapon() && e instanceof Player) {
			Player p = (Player) e;
			currentWeapon = 0;
			getWeapon().setController(p);
		}
	}
	
	public void dismount() {
		if (e != null) SEATED.computeIfPresent(e, (entity, seats) -> seats == 1 ? null : seats - 1);
		e = null;
		if(hasWeapon()) {
			getWeapon().disconnect();
		}
		if(mountedIsVehicle()) {
			mountedVehicle.setParent(null);
			mountedVehicle = null;
		}
	}
	
	public void changeWeapon() {
		if(!(e instanceof Player)) return;
		Player p = (Player) e;
		if(cooldown.containsKey(p)) {
			if(cooldown.get(p) > System.currentTimeMillis()) {
				return;
			}
		}
		cooldown.put(p, System.currentTimeMillis()+300);
		if(weapons.size() <= 1) return;
		getWeapon().disconnect();
		currentWeapon++;
		if(currentWeapon >= weapons.size()) {
			currentWeapon = 0;
		}
		getWeapon().setController(p);
	}
	
	public boolean hasWeapon() {
		return weapons.size() > 0;
	}
	public ActiveWeapon getWeapon() {
		return weapons.get(currentWeapon);
	}
	public void connectWeapon(ActiveWeapon w) {
		weapons.add(w);
	}
	
	public boolean mountedIsVehicle() {
		return mountedVehicle != null;
	}
	public ActiveVehicle getMountedVehicle() {
		return mountedVehicle;
	}

	public SeatType getType() {
		return type;
	}

	public String getBone() {
		return bone;
	}
	
	
}
