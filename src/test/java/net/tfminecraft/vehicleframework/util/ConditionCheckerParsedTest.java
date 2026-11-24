package net.tfminecraft.vehicleframework.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import net.tfminecraft.vehicleframework.enums.Component;
import net.tfminecraft.vehicleframework.enums.State;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.component.Engine;
import net.tfminecraft.vehicleframework.vehicles.component.Wings;
import net.tfminecraft.vehicleframework.vehicles.component.propulsion.Throttle;
import net.tfminecraft.vehicleframework.vehicles.seat.Seat;

/** Parsed conditions answer exactly as the text was answered before, for well-formed and malformed text alike. */
class ConditionCheckerParsedTest {
    private static final List<String> TYPES = List.of("state", " STATE ", "lift", "passengers", "seat_filled", "seat_empty",
            "is_passenger", "Has_Fuel", "throttle_less_than", "throttle_more_than", "OR", "and", "health_percent", "unknown",
            "", " ", "\u0130s_passenger", "lift(");
    private static final List<String> VALUES = List.of("true", "false", "TRUE", " false ", "maybe", "", " ", "ground", "flying",
            "driver", "empty", "missing", "50", "-5", "40", "x", "has_fuel=true;passengers=false", "has_fuel=false;passengers=true",
            "=true;has_fuel=true", "has_fuel=true;bad", "bad;has_fuel=true", "state=ground;", "passengers=true;has_fuel=true",
            "throttle_less_than=50;throttle_more_than=90", "wings;at_least=50", "component=engine;less_than=30",
            "engine;more_than=x", "hull;at_most=10;at_least=5", "wings;engine;at_least=1", "nope;at_least=1", "wings;foo=1",
            ";;wings;;at_most=100", "component=hull;more_than=5", "engine;at_most=90", "OR=has_fuel=true", "wings");

    @Test
    void everyTypeAndValueGivesTheOldAnswerInEveryVehicleState() {
        List<ActiveVehicle> vehicles = vehicles();
        for (String type : TYPES) {
            for (String value : VALUES) {
                ConditionChecker.Condition parsed = ConditionChecker.compile(type, value);
                for (ActiveVehicle vehicle : vehicles) {
                    String label = type + "(" + value + ")";
                    assertEquals(Legacy.checkCondition(vehicle, type, value), parsed.test(vehicle), label);
                    assertEquals(Legacy.checkCondition(vehicle, type, value), ConditionChecker.checkCondition(vehicle, type, value), label);
                }
            }
        }
        for (ActiveVehicle vehicle : vehicles) {
            assertEquals(Legacy.checkCondition(vehicle, null, "true"), ConditionChecker.checkCondition(vehicle, null, "true"));
            assertEquals(Legacy.checkCondition(vehicle, "has_fuel", null), ConditionChecker.checkCondition(vehicle, "has_fuel", null));
        }
    }

    @Test
    void conditionListsGiveTheOldAnswerAndAParsedListCanBeTestedAgain() {
        List<ActiveVehicle> vehicles = vehicles();
        List<String> entries = new ArrayList<>(List.of("(true)", "has_fuel(true", "passengers(maybe)", "x()", "unknown(true)", "a)"));
        for (String type : TYPES) for (String value : VALUES) entries.add(type + "(" + value + ")");
        Random random = new Random(1124);
        for (int round = 0; round < 3000; round++) {
            List<String> conditions = new ArrayList<>();
            for (int i = random.nextInt(4); i > 0; i--) conditions.add(entries.get(random.nextInt(entries.size())));
            if (random.nextInt(20) == 0) conditions.add(null);
            ConditionChecker.Condition parsed = ConditionChecker.compile(conditions);
            for (ActiveVehicle vehicle : vehicles) {
                boolean old = Legacy.checkConditions(vehicle, conditions);
                assertEquals(old, parsed.test(vehicle), conditions.toString());
                assertEquals(old, parsed.test(vehicle), "again: " + conditions);
                assertEquals(old, ConditionChecker.checkConditions(vehicle, conditions), conditions.toString());
            }
        }
    }

    @Test
    void malformedConditionsAreAlwaysFalse() {
        for (ActiveVehicle vehicle : vehicles()) {
            assertFalse(ConditionChecker.compile(Arrays.asList("has_fuel(true)", null)).test(vehicle));
            assertFalse(ConditionChecker.compile(List.of("passengers")).test(vehicle));
            assertFalse(ConditionChecker.compile("OR", "=true;has_fuel=true").test(vehicle));
            assertFalse(ConditionChecker.compile("AND", "has_fuel=true;bad").test(vehicle));
            assertFalse(ConditionChecker.compile("health_percent", "wings;at_least=x").test(vehicle));
            assertTrue(ConditionChecker.compile(List.of()).test(vehicle));
        }
    }

    /** Vehicles that differ in every value a condition reads. */
    private static List<ActiveVehicle> vehicles() {
        List<ActiveVehicle> all = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            ActiveVehicle v = mock(ActiveVehicle.class, RETURNS_DEEP_STUBS);
            when(v.getStateHandler().getCurrentState().getType()).thenReturn(i % 2 == 0 ? State.GROUND : State.FLYING);
            when(v.getSeatHandler().hasPassengers()).thenReturn((i & 2) != 0);
            when(v.hasParent()).thenReturn((i & 4) != 0);
            when(v.hasFuel()).thenReturn(i % 3 == 0);
            if (i == 5) {
                when(v.getThrottle()).thenReturn(null);
            } else {
                Throttle throttle = mock(Throttle.class);
                when(throttle.getCurrent()).thenReturn(i * 15 - 10);
                when(v.getThrottle()).thenReturn(throttle);
            }
            Seat driver = mock(Seat.class), empty = mock(Seat.class);
            when(driver.isOccupied()).thenReturn(true);
            when(v.getAccessPanel().getSeat("driver")).thenReturn(driver);
            when(v.getAccessPanel().getSeat("empty")).thenReturn(empty);
            when(v.getAccessPanel().getSeat("missing")).thenReturn(null);
            boolean flies = i >= 2;
            when(v.hasComponent(Component.WINGS)).thenReturn(flies);
            when(v.hasComponent(Component.ENGINE)).thenReturn(i != 3);
            when(v.hasComponent(Component.HULL)).thenReturn(i % 2 == 1);
            Wings wings = mock(Wings.class, RETURNS_DEEP_STUBS);
            when(wings.getLift()).thenReturn(i * .2);
            when(v.getComponent(Component.WINGS)).thenReturn(wings);
            Engine engine = mock(Engine.class, RETURNS_DEEP_STUBS);
            when(engine.getSpeed()).thenReturn(.8);
            when(engine.getThrottle().getCurrent()).thenReturn(i * 15 - 10);
            when(engine.getHealthData().getHealthPercentage()).thenReturn(i * 13);
            when(v.getComponent(Component.ENGINE)).thenReturn(engine);
            when(v.getComponent(Component.WINGS).getHealthData().getHealthPercentage()).thenReturn(100 - i * 11);
            when(v.getComponent(Component.HULL).getHealthData().getHealthPercentage()).thenReturn(i * 3);
            all.add(v);
        }
        return all;
    }

    /** ConditionChecker as it was before conditions were parsed once, kept to compare answers. */
        static final class Legacy {
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
}
