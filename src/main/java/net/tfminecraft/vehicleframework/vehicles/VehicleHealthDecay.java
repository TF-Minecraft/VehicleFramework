package net.tfminecraft.vehicleframework.vehicles;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.tfminecraft.vehicleframework.data.HealthData;
import net.tfminecraft.vehicleframework.enums.Component;
import net.tfminecraft.vehicleframework.vehicles.component.Balloon;
import net.tfminecraft.vehicleframework.vehicles.component.Pump;
import net.tfminecraft.vehicleframework.vehicles.component.SinkableHull;
import net.tfminecraft.vehicleframework.vehicles.component.VehicleComponent;
import net.tfminecraft.vehicleframework.vehicles.handlers.ComponentHandler;
import net.tfminecraft.vehicleframework.vehicles.handlers.WeaponHandler;
import net.tfminecraft.vehicleframework.weapons.ActiveWeapon;
import net.tfminecraft.vehicleframework.weapons.Weapon;

/**
 * Generic health decay for spawned and stored vehicles. Callers supply a
 * {@link HealthDecayRule}; this class does not know about upkeep.
 */
public final class VehicleHealthDecay {
	private static final String HULL = key(Component.HULL);
	private static final String PUMP = key(Component.PUMP);
	private static final String BALLOON = key(Component.BALLOON);

	private VehicleHealthDecay() {}

	public interface MaxHealthLookup {
		double componentMaxHealth(String componentTypeKey);

		double weaponMaxHealth(String weaponId);
	}

	public static double nextDamage(
			double currentDamage,
			double maxHealth,
			double fractionOfMax,
			double minHealthFraction) {
		if (maxHealth <= 0.0) {
			return currentDamage;
		}
		double fraction = Math.max(0.0, fractionOfMax);
		double minRemaining = Math.max(0.0, Math.min(1.0, minHealthFraction));
		double maxAllowedDamage = Math.max(currentDamage, maxHealth * (1.0 - minRemaining));
		double next = currentDamage + maxHealth * fraction;
		if (next > maxAllowedDamage) {
			next = maxAllowedDamage;
		}
		if (next < 0.0) {
			next = 0.0;
		}
		return next;
	}

	public static void applyToLive(
			ActiveVehicle vehicle,
			double fractionOfMax,
			double minHealthFraction) {
		applyToLive(vehicle, HealthDecayRule.of(fractionOfMax, minHealthFraction));
	}

	public static void applyToLive(ActiveVehicle vehicle, HealthDecayRule rule) {
		if (vehicle == null || rule == null) {
			return;
		}
		if (vehicle.getComponents() != null) {
			SinkableHull afloat = null;
			for (VehicleComponent component : vehicle.getComponents()) {
				if (component == null || component.getHealthData() == null) {
					continue;
				}
				if (rule.keepAfloat() && component instanceof SinkableHull hull && vehicle.shouldFloat()) {
					afloat = hull;
					continue;
				}
				decay(component.getHealthData(), rule.fractionOfMax(), componentFloor(component, rule));
			}
			if (afloat != null) {
				double floor = Math.max(componentFloor(afloat, rule), liveSinkThreshold(afloat, rule) / 100.0);
				decay(afloat.getHealthData(), rule.fractionOfMax(), floor);
			}
		}
		WeaponHandler weapons = vehicle.getWeaponHandler();
		if (weapons != null && weapons.getWeapons() != null) {
			for (ActiveWeapon weapon : weapons.getWeapons()) {
				if (weapon == null || weapon.getHealthData() == null) {
					continue;
				}
				decay(weapon.getHealthData(), rule.fractionOfMax(), rule.minHealthFor(weapon.getId()));
			}
		}
	}

	public static boolean applyToJson(
			JsonObject root,
			MaxHealthLookup lookup,
			double fractionOfMax,
			double minHealthFraction) {
		if (root == null || lookup == null) {
			return false;
		}
		return apply(root, lookup, HealthDecayRule.of(fractionOfMax, minHealthFraction), SafeFloors.NONE);
	}

	/** Applies {@code rule} to a stored payload, reading max health and safe floors from {@code template}. */
	public static boolean applyToJson(JsonObject root, Vehicle template, HealthDecayRule rule) {
		if (root == null || template == null || rule == null) {
			return false;
		}
		return apply(root, lookupFromTemplate(template), rule, SafeFloors.of(template, rule));
	}

	public static MaxHealthLookup lookupFromTemplate(Vehicle template) {
		Map<String, Double> components = new HashMap<>();
		Map<String, Double> weapons = new HashMap<>();
		if (template != null && template.getComponentHandler() != null) {
			for (VehicleComponent component : template.getComponentHandler().getComponents()) {
				if (component == null || component.getType() == null || component.getHealthData() == null) {
					continue;
				}
				components.put(
						key(component.getType()),
						component.getHealthData().getHealth());
			}
		}
		if (template != null && template.getWeapons() != null) {
			for (Weapon weapon : template.getWeapons()) {
				if (weapon == null || weapon.getId() == null || weapon.getHealthData() == null) {
					continue;
				}
				weapons.put(weapon.getId(), weapon.getHealthData().getHealth());
			}
		}
		return new MaxHealthLookup() {
			@Override
			public double componentMaxHealth(String componentTypeKey) {
				if (componentTypeKey == null) {
					return 0.0;
				}
				Double value = components.get(componentTypeKey.toLowerCase(Locale.ROOT));
				return value == null ? 0.0 : value;
			}

			@Override
			public double weaponMaxHealth(String weaponId) {
				if (weaponId == null) {
					return 0.0;
				}
				Double value = weapons.get(weaponId);
				if (value != null) {
					return value;
				}
				for (Map.Entry<String, Double> entry : weapons.entrySet()) {
					if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(weaponId)) {
						return entry.getValue();
					}
				}
				return 0.0;
			}
		};
	}

	/**
	 * The hull's sink threshold once its pump has decayed as far as this rule takes it. Later steps
	 * keep weakening the pump, which raises the threshold, and decay never repairs the hull, so the
	 * hull has to stop at the threshold of the fully decayed pump from the first step.
	 */
	private static double liveSinkThreshold(SinkableHull hull, HealthDecayRule rule) {
		if (!hull.hasPump()) {
			return SinkableHull.sinkThreshold(null);
		}
		HealthData pump = hull.getPump().getHealthData();
		return pumpSinkThreshold(hull.getPump().getBasePower(), pump.getHealthPercentage(), pump.getHealth(), rule);
	}

	private static double pumpSinkThreshold(int basePower, int pumpHealth, double pumpMaxHealth, HealthDecayRule rule) {
		int eventual = pumpHealth;
		if (rule.fractionOfMax() > 0) {
			double floor = Math.max(0.0, Math.min(1.0, rule.minHealthFor(PUMP)));
			eventual = Math.min(pumpHealth, HealthData.percentage(pumpMaxHealth * (1.0 - floor), pumpMaxHealth));
		}
		return SinkableHull.sinkThreshold(Pump.powerAt(basePower, eventual));
	}

	private static double componentFloor(VehicleComponent component, HealthDecayRule rule) {
		double floor = rule.minHealthFor(component.getType() == null ? null : key(component.getType()));
		if (rule.keepAloft() && component instanceof Balloon) {
			floor = Math.max(floor, Balloon.NEUTRAL_LIFT_HEALTH);
		}
		return floor;
	}

	private static void decay(HealthData health, double fractionOfMax, double minHealthFraction) {
		health.setDamage(nextDamage(health.getDamage(), health.getHealth(), fractionOfMax, minHealthFraction));
	}

	private static boolean apply(JsonObject root, MaxHealthLookup lookup, HealthDecayRule rule, SafeFloors safe) {
		boolean changed = false;
		JsonObject components = section(root.get("components"));
		if (components != null) {
			String afloat = null;
			for (Map.Entry<String, JsonElement> entry : components.entrySet()) {
				String part = entry.getKey();
				if (safe.sinkableHull() && HULL.equalsIgnoreCase(part)) {
					afloat = part;
					continue;
				}
				double floor = rule.minHealthFor(part);
				if (safe.balloon() && BALLOON.equalsIgnoreCase(part)) {
					floor = Math.max(floor, Balloon.NEUTRAL_LIFT_HEALTH);
				}
				changed |= decayPart(entry.getValue(), lookup.componentMaxHealth(part), rule.fractionOfMax(), floor);
			}
			if (afloat != null) {
				double floor = Math.max(rule.minHealthFor(afloat), safe.sinkThreshold(components, rule) / 100.0);
				changed |= decayPart(components.get(afloat), lookup.componentMaxHealth(afloat), rule.fractionOfMax(), floor);
			}
		}
		JsonObject weapons = section(root.get("weapons"));
		if (weapons != null) {
			for (Map.Entry<String, JsonElement> entry : weapons.entrySet()) {
				String weapon = entry.getKey();
				changed |= decayPart(entry.getValue(), lookup.weaponMaxHealth(weapon), rule.fractionOfMax(),
						rule.minHealthFor(weapon));
			}
		}
		return changed;
	}

	private static boolean decayPart(JsonElement partElement, double maxHealth, double fractionOfMax, double floor) {
		if (partElement == null || !partElement.isJsonObject() || maxHealth <= 0.0) {
			return false;
		}
		JsonObject part = partElement.getAsJsonObject();
		double current = jsonDouble(part, "damage");
		double next = nextDamage(current, maxHealth, fractionOfMax, floor);
		if (Double.compare(current, next) == 0) {
			return false;
		}
		part.addProperty("damage", next);
		return true;
	}

	private static JsonObject section(JsonElement element) {
		return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
	}

	private static JsonObject member(JsonObject section, String name) {
		for (Map.Entry<String, JsonElement> entry : section.entrySet()) {
			if (entry.getKey().equalsIgnoreCase(name)) {
				return section(entry.getValue());
			}
		}
		return null;
	}

	private static double jsonDouble(JsonObject object, String key) {
		if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
			return 0.0;
		}
		try {
			return object.get(key).getAsDouble();
		} catch (RuntimeException e) {
			return 0.0;
		}
	}

	private static String key(Component type) {
		return type.toString().toLowerCase(Locale.ROOT);
	}

	/** Which stored parts a rule keeps safe, read from the vehicle's template. */
	private record SafeFloors(boolean balloon, boolean sinkableHull, Pump pump) {
		static final SafeFloors NONE = new SafeFloors(false, false, null);

		static SafeFloors of(Vehicle template, HealthDecayRule rule) {
			ComponentHandler handler = template.getComponentHandler();
			if (handler == null) {
				return NONE;
			}
			boolean balloon = rule.keepAloft() && handler.getComponent(Component.BALLOON) != null;
			boolean floats = template.getBehaviourHandler() != null && template.getBehaviourHandler().shouldFloat();
			if (rule.keepAfloat() && floats && handler.getComponent(Component.HULL) instanceof SinkableHull hull) {
				return new SafeFloors(balloon, true, hull.hasPump() ? hull.getPump() : null);
			}
			return new SafeFloors(balloon, false, null);
		}

		/** The hull's sink threshold once the stored pump has decayed as far as the rule takes it. */
		double sinkThreshold(JsonObject components, HealthDecayRule rule) {
			if (pump == null) {
				return SinkableHull.sinkThreshold(null);
			}
			int pumpHealth = HealthData.percentage(
					jsonDouble(member(components, PUMP), "damage"),
					pump.getHealthData().getHealth());
			return pumpSinkThreshold(pump.getBasePower(), pumpHealth, pump.getHealthData().getHealth(), rule);
		}
	}
}
