package net.tfminecraft.vehicleframework.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.tfminecraft.vehicleframework.VehicleFramework;
import net.tfminecraft.vehicleframework.enums.Component;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.component.Engine;
import net.tfminecraft.vehicleframework.vehicles.component.Wings;
import net.tfminecraft.vehicleframework.vehicles.component.propulsion.Throttle;

public class ConditionChecker {
	public static boolean checkConditions(ActiveVehicle vehicle, List<String> conditions) {
        for (String condition : conditions) {
            if (condition == null) return false;
            int start = condition.indexOf('(');
            if (start <= 0 || !condition.endsWith(")")) return false;
            String type = condition.substring(0, start).trim();
            String value = condition.substring(start + 1, condition.length() - 1).trim();
            if (!checkCondition(vehicle, type, value)) return false;
        }
        return true;
	}
	
	public static boolean checkCondition(ActiveVehicle vehicle, String type, String value) {
        if (type == null || value == null || value.isBlank()) return false;
        type = type.trim();
        value = value.trim();
        if (java.util.Set.of("lift", "passengers", "is_passenger", "has_fuel").contains(type.toLowerCase(java.util.Locale.ROOT))
                && !value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) return false;
		if(type.equalsIgnoreCase("state")) {
			if(!vehicle.getStateHandler().getCurrentState().getType().toString().equalsIgnoreCase(value)) return false;
		} else if(type.equalsIgnoreCase("lift")) {
			if(!vehicle.hasComponent(Component.WINGS)) return false;
			Wings wings = (Wings) vehicle.getComponent(Component.WINGS);
			if(!vehicle.hasComponent(Component.ENGINE)) return false;
			Engine engine = (Engine) vehicle.getComponent(Component.ENGINE);
			double lift = wings.getLift() * engine.getSpeed();
            double throttleFactor = Math.max(0, engine.getThrottle().getCurrent()) / 100.0;
            lift *= throttleFactor;
			if(lift < 0.49 && value.equalsIgnoreCase("true")) return false;
			if(lift >= 0.49 && value.equalsIgnoreCase("false")) return false;
		} else if(type.equalsIgnoreCase("passengers")) {
			if(!(vehicle.getSeatHandler().hasPassengers() == Boolean.parseBoolean(value))) return false;
		} else if(type.equalsIgnoreCase("seat_filled")) {
			if(vehicle.getAccessPanel().getSeat(value) == null || !vehicle.getAccessPanel().getSeat(value).isOccupied()) return false;
		} else if(type.equalsIgnoreCase("seat_empty")) {
			if(vehicle.getAccessPanel().getSeat(value) == null || vehicle.getAccessPanel().getSeat(value).isOccupied()) return false;
		} else if(type.equalsIgnoreCase("is_passenger")) {
			boolean passenger = vehicle.hasParent();
			if(value.equalsIgnoreCase("true") && !passenger) return false;
			if(value.equalsIgnoreCase("false") && passenger) return false;
		} else if(type.equalsIgnoreCase("has_fuel")) {
			boolean hasFuel = vehicle.hasFuel();
			if(value.equalsIgnoreCase("true") && !hasFuel) return false;
			if(value.equalsIgnoreCase("false") && hasFuel) return false;
		} else if(type.equalsIgnoreCase("throttle_less_than")) {
			Throttle throttle = vehicle.getThrottle();
			if(throttle == null) return false;
			int amount = throttle.getCurrent();
			try { if(amount > Integer.parseInt(value)) return false; } catch (NumberFormatException invalid) { return false; }
			return true;
		} else if(type.equalsIgnoreCase("throttle_more_than")) {
			Throttle throttle = vehicle.getThrottle();
			if(throttle == null) return false;
			int amount = throttle.getCurrent();
			try { if(amount < Integer.parseInt(value)) return false; } catch (NumberFormatException invalid) { return false; }
			return true;
		} else if(type.equalsIgnoreCase("OR")) {
			if(!orStatement(vehicle, value)) return false;
		} else if(type.equalsIgnoreCase("AND")) {
			if(!andStatement(vehicle, value)) return false;
		} else if(type.equalsIgnoreCase("health_percent")) {
			return checkHealthPercent(vehicle, value);
		} else {
            return false;
        }
		return true;
	}

	private static boolean checkHealthPercent(ActiveVehicle vehicle, String value) {
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
					return false;
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
					return false;
				}
				try {
					threshold = Integer.parseInt(raw);
				} catch (NumberFormatException e) {
					return false;
				}
				comparison = key.toLowerCase(java.util.Locale.ROOT);
				continue;
			}
			return false;
		}
		if (componentName == null || comparison == null || threshold == null) {
			return false;
		}
		Component component;
		try {
			component = Component.valueOf(componentName.toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return false;
		}
		if (!vehicle.hasComponent(component)) {
			return false;
		}
		int percent = vehicle.getComponent(component).getHealthData().getHealthPercentage();
		if (comparison.equals("less_than")) return percent < threshold;
		if (comparison.equals("more_than")) return percent > threshold;
		if (comparison.equals("at_most")) return percent <= threshold;
		return percent >= threshold; // The parser accepts only these four comparisons.
	}
	
    private static boolean orStatement(ActiveVehicle vehicle, String conditions) {
        for (String condition : conditions.split(";", -1)) {
            int separator = condition.indexOf('=');
            if (separator <= 0) return false;
            if (checkCondition(vehicle, condition.substring(0, separator), condition.substring(separator + 1))) return true;
        }
        return false;
    }

    private static boolean andStatement(ActiveVehicle vehicle, String conditions) {
        for (String condition : conditions.split(";", -1)) {
            int separator = condition.indexOf('=');
            if (separator <= 0 || !checkCondition(vehicle, condition.substring(0, separator), condition.substring(separator + 1))) return false;
        }
        return true;
    }
}
