package net.tfminecraft.vehicleframework.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.tfminecraft.vehicleframework.enums.Component;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.component.Engine;
import net.tfminecraft.vehicleframework.vehicles.component.Wings;
import net.tfminecraft.vehicleframework.vehicles.component.propulsion.Throttle;

/**
 * Vehicle conditions written as text in the vehicle files. Each text is parsed into a
 * {@link Condition} that only reads the vehicle, so rotation targets, which test theirs every tick,
 * parse them once. A malformed condition parses to one that is always false.
 */
public class ConditionChecker {
	/** A parsed condition. */
	@FunctionalInterface
	public interface Condition {
		boolean test(ActiveVehicle vehicle);
	}

	/** Always false. Each instance is its own object, so the two markers below stay distinct. */
	private static final class Never implements Condition {
		@Override
		public boolean test(ActiveVehicle vehicle) {
			return false;
		}
	}

	private static final Condition FALSE = new Never();
	/** In an OR list, an entry without a type ends the list as false whatever came after it. */
	private static final Condition END_FALSE = new Never();
	private static final Set<String> BOOLEAN_TYPES = Set.of("lift", "passengers", "is_passenger", "has_fuel");

	public static boolean checkConditions(ActiveVehicle vehicle, List<String> conditions) {
		return compile(conditions).test(vehicle);
	}

	/** Conditions written as {@code type(value)}; all must hold, tested in order. */
	public static Condition compile(List<String> conditions) {
		List<Condition> parts = new ArrayList<>(conditions.size());
		for (String condition : conditions) {
			Condition part = compileEntry(condition);
			parts.add(part);
			// Nothing after an entry that is always false is ever tested.
			if (part == FALSE) break;
		}
		return allOf(parts);
	}

	private static Condition compileEntry(String condition) {
		if (condition == null) return FALSE;
		int start = condition.indexOf('(');
		if (start <= 0 || !condition.endsWith(")")) return FALSE;
		String type = condition.substring(0, start).trim();
		String value = condition.substring(start + 1, condition.length() - 1).trim();
		return compile(type, value);
	}

	public static boolean checkCondition(ActiveVehicle vehicle, String type, String value) {
		return compile(type, value).test(vehicle);
	}

	public static Condition compile(String type, String value) {
		if (type == null || value == null || value.isBlank()) return FALSE;
		String kind = type.trim();
		String arg = value.trim();
		if (BOOLEAN_TYPES.contains(kind.toLowerCase(Locale.ROOT))
				&& !arg.equalsIgnoreCase("true") && !arg.equalsIgnoreCase("false")) return FALSE;
		boolean isTrue = arg.equalsIgnoreCase("true");
		boolean isFalse = arg.equalsIgnoreCase("false");
		if (kind.equalsIgnoreCase("state")) {
			return vehicle -> vehicle.getStateHandler().getCurrentState().getType().toString().equalsIgnoreCase(arg);
		} else if (kind.equalsIgnoreCase("lift")) {
			return vehicle -> {
				if (!vehicle.hasComponent(Component.WINGS)) return false;
				Wings wings = (Wings) vehicle.getComponent(Component.WINGS);
				if (!vehicle.hasComponent(Component.ENGINE)) return false;
				Engine engine = (Engine) vehicle.getComponent(Component.ENGINE);
				double lift = wings.getLift() * engine.getSpeed();
				double throttleFactor = Math.max(0, engine.getThrottle().getCurrent()) / 100.0;
				lift *= throttleFactor;
				if (lift < 0.49 && isTrue) return false;
				return !(lift >= 0.49 && isFalse);
			};
		} else if (kind.equalsIgnoreCase("passengers")) {
			boolean wanted = Boolean.parseBoolean(arg);
			return vehicle -> vehicle.getSeatHandler().hasPassengers() == wanted;
		} else if (kind.equalsIgnoreCase("seat_filled")) {
			return vehicle -> vehicle.getAccessPanel().getSeat(arg) != null && vehicle.getAccessPanel().getSeat(arg).isOccupied();
		} else if (kind.equalsIgnoreCase("seat_empty")) {
			return vehicle -> vehicle.getAccessPanel().getSeat(arg) != null && !vehicle.getAccessPanel().getSeat(arg).isOccupied();
		} else if (kind.equalsIgnoreCase("is_passenger")) {
			return vehicle -> matches(vehicle.hasParent(), isTrue, isFalse);
		} else if (kind.equalsIgnoreCase("has_fuel")) {
			return vehicle -> matches(vehicle.hasFuel(), isTrue, isFalse);
		} else if (kind.equalsIgnoreCase("throttle_less_than")) {
			Integer limit = parseInt(arg);
			if (limit == null) return FALSE;
			return vehicle -> {
				Throttle throttle = vehicle.getThrottle();
				return throttle != null && throttle.getCurrent() <= limit;
			};
		} else if (kind.equalsIgnoreCase("throttle_more_than")) {
			Integer limit = parseInt(arg);
			if (limit == null) return FALSE;
			return vehicle -> {
				Throttle throttle = vehicle.getThrottle();
				return throttle != null && throttle.getCurrent() >= limit;
			};
		} else if (kind.equalsIgnoreCase("OR")) {
			return orStatement(arg);
		} else if (kind.equalsIgnoreCase("AND")) {
			return andStatement(arg);
		} else if (kind.equalsIgnoreCase("health_percent")) {
			return healthPercent(arg);
		}
		return FALSE;
	}

	/** Only an exact true or false constrains the value; anything else holds either way. */
	private static boolean matches(boolean actual, boolean isTrue, boolean isFalse) {
		if (isTrue && !actual) return false;
		return !(isFalse && actual);
	}

	private static Integer parseInt(String value) {
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException invalid) {
			return null;
		}
	}

	private static Condition healthPercent(String value) {
		String componentName = null;
		String comparison = null;
		Integer threshold = null;
		for (String token : value.split(";")) {
			String part = token.trim();
			if (part.isEmpty()) {
				continue;
			}
			int eq = part.indexOf('=');
			if (eq < 0) {
				if (componentName != null) {
					return FALSE;
				}
				componentName = part;
				continue;
			}
			String key = part.substring(0, eq).trim();
			String raw = part.substring(eq + 1).trim();
			if (key.equalsIgnoreCase("component")) {
				componentName = raw;
				continue;
			}
			if (key.equalsIgnoreCase("less_than") || key.equalsIgnoreCase("more_than")
					|| key.equalsIgnoreCase("at_most") || key.equalsIgnoreCase("at_least")) {
				if (comparison != null) {
					return FALSE;
				}
				threshold = parseInt(raw);
				if (threshold == null) {
					return FALSE;
				}
				comparison = key.toLowerCase(Locale.ROOT);
				continue;
			}
			return FALSE;
		}
		if (componentName == null || comparison == null) {
			return FALSE;
		}
		Component component;
		try {
			component = Component.valueOf(componentName.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return FALSE;
		}
		int limit = threshold;
		String compare = comparison;
		return vehicle -> {
			if (!vehicle.hasComponent(component)) {
				return false;
			}
			int percent = vehicle.getComponent(component).getHealthData().getHealthPercentage();
			if (compare.equals("less_than")) return percent < limit;
			if (compare.equals("more_than")) return percent > limit;
			if (compare.equals("at_most")) return percent <= limit;
			return percent >= limit; // The parser accepts only these four comparisons.
		};
	}

	/** True at the first entry that holds; false at the end, or at an entry without a type. */
	private static Condition orStatement(String conditions) {
		List<Condition> parts = new ArrayList<>();
		for (String condition : conditions.split(";", -1)) {
			int separator = condition.indexOf('=');
			if (separator <= 0) {
				parts.add(END_FALSE);
				break;
			}
			parts.add(compile(condition.substring(0, separator), condition.substring(separator + 1)));
		}
		Condition[] all = parts.toArray(new Condition[0]);
		return vehicle -> {
			for (Condition part : all) {
				if (part == END_FALSE) return false;
				if (part.test(vehicle)) return true;
			}
			return false;
		};
	}

	/** Every entry must hold; one without a type is false. */
	private static Condition andStatement(String conditions) {
		List<Condition> parts = new ArrayList<>();
		for (String condition : conditions.split(";", -1)) {
			int separator = condition.indexOf('=');
			if (separator <= 0) {
				parts.add(FALSE);
				break;
			}
			parts.add(compile(condition.substring(0, separator), condition.substring(separator + 1)));
		}
		return allOf(parts);
	}

	/** True when every part holds, tested in order up to the first that does not. */
	private static Condition allOf(List<Condition> parts) {
		Condition[] all = parts.toArray(new Condition[0]);
		return vehicle -> {
			for (Condition part : all) {
				if (!part.test(vehicle)) return false;
			}
			return true;
		};
	}
}
