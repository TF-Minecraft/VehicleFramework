package net.tfminecraft.vehicleframework.vehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.tfminecraft.vehicleframework.data.HealthData;
import net.tfminecraft.vehicleframework.test.RegistryFixture;
import net.tfminecraft.vehicleframework.vehicles.component.Balloon;
import net.tfminecraft.vehicleframework.vehicles.component.Pump;
import net.tfminecraft.vehicleframework.vehicles.component.SinkableHull;
import net.tfminecraft.vehicleframework.vehicles.component.VehicleComponent;
import net.tfminecraft.vehicleframework.vehicles.handlers.BehaviourHandler;
import net.tfminecraft.vehicleframework.vehicles.handlers.ComponentHandler;
import net.tfminecraft.vehicleframework.vehicles.handlers.WeaponHandler;
import net.tfminecraft.vehicleframework.weapons.ActiveWeapon;
import net.tfminecraft.vehicleframework.weapons.Weapon;

class HealthDecayRuleTest {
	private static final double EPSILON = 1e-9;

	@BeforeAll
	static void registry() {
		RegistryFixture.initialize();
	}

	@Test
	void partFloorsMatchAnyCaseAndFallBackToTheDefault() {
		Map<String, Double> parts = new HashMap<>();
		parts.put("Hull", .5);
		parts.put(null, .9);
		parts.put("pump", null);
		HealthDecayRule rule = new HealthDecayRule(.2, .03, parts, true, false);

		assertEquals(.5, rule.minHealthFor("HULL"));
		assertEquals(.03, rule.minHealthFor("pump"));
		assertEquals(.03, rule.minHealthFor(null));
		assertEquals(Map.of("hull", .5), rule.partMinHealthFractions());
		assertTrue(new HealthDecayRule(.2, .03, null, false, false).partMinHealthFractions().isEmpty());
		assertEquals(new HealthDecayRule(.2, .03, Map.of(), false, false), HealthDecayRule.of(.2, .03));
	}

	@Test
	void sinkThresholdFollowsThePumpAndNeverExceedsEightyPercent() {
		assertEquals(80, SinkableHull.sinkThreshold(null));
		assertEquals(55, SinkableHull.sinkThreshold(45));
		assertEquals(80, SinkableHull.sinkThreshold(10));
		assertEquals(45, Pump.powerAt(50, 90));
		assertEquals(90, HealthData.percentage(10, 100));

		SinkableHull hull = new SinkableHull(config(400));
		assertEquals(80, hull.getSinkThreshold());
		Pump pump = new Pump(pumpConfig());
		hull.setPump(pump);
		assertEquals(50, hull.getSinkThreshold());
	}

	@Test
	void liveDecayKeepsAFloatingHullAboveWaterAndABalloonAtNeutralLift() {
		SinkableHull hull = new SinkableHull(config(400));
		Balloon balloon = new Balloon(config(200));
		VehicleComponent untyped = mock(VehicleComponent.class);
		HealthData untypedHealth = new HealthData(100, 0, 1);
		when(untyped.getHealthData()).thenReturn(untypedHealth);
		ActiveWeapon cannon = mock(ActiveWeapon.class);
		HealthData cannonHealth = new HealthData(100, 0, 1);
		when(cannon.getId()).thenReturn("cannon");
		when(cannon.getHealthData()).thenReturn(cannonHealth);
		ActiveVehicle vehicle = live(true, List.of(hull, balloon, untyped), cannon);

		VehicleHealthDecay.applyToLive(vehicle, new HealthDecayRule(1, .03, Map.of("Cannon", .5), true, true));

		assertEquals(80, hull.getHealthData().getHealthPercentage());
		assertEquals(50, balloon.getHealthData().getHealthPercentage());
		assertEquals(3, untypedHealth.getHealthPercentage());
		assertEquals(50, cannonHealth.getHealthPercentage());
	}

	@Test
	void liveHullFloorReadsThePumpAfterThePumpHasDecayed() {
		SinkableHull hull = new SinkableHull(config(400));
		Pump pump = new Pump(pumpConfig());
		hull.setPump(pump);
		ActiveVehicle vehicle = live(true, List.of(hull, pump), null);

		VehicleHealthDecay.applyToLive(vehicle, new HealthDecayRule(1, .03, Map.of("pump", .9), true, false));

		assertEquals(90, pump.getHealthData().getHealthPercentage());
		assertEquals(55, hull.getHealthData().getHealthPercentage());
	}

	@Test
	void liveSafeFloorsOnlyApplyWhenAskedAndWhenTheVehicleFloats() {
		SinkableHull hull = new SinkableHull(config(400));
		Balloon balloon = new Balloon(config(200));
		VehicleHealthDecay.applyToLive(live(false, List.of(hull, balloon), null), new HealthDecayRule(1, .03, Map.of(), true, false));
		assertEquals(3, hull.getHealthData().getHealthPercentage());
		assertEquals(3, balloon.getHealthData().getHealthPercentage());

		SinkableHull other = new SinkableHull(config(400));
		VehicleHealthDecay.applyToLive(live(true, List.of(other), null), HealthDecayRule.of(1, .03));
		assertEquals(3, other.getHealthData().getHealthPercentage());

		VehicleHealthDecay.applyToLive(null, HealthDecayRule.of(1, .03));
		VehicleHealthDecay.applyToLive(live(true, List.of(other), null), null);
		assertEquals(3, other.getHealthData().getHealthPercentage());
	}

	@Test
	void storedDecayUsesTheTemplatesSafeFloors() {
		SinkableHull hull = new SinkableHull(config(400));
		Vehicle template = template(true, hull, new Pump(pumpConfig()), new Balloon(config(200)));
		JsonObject stored = stored("{components:{Hull:{damage:0},pump:{damage:0},balloon:{damage:0}},weapons:{cannon:{damage:0}}}");

		assertTrue(VehicleHealthDecay.applyToJson(stored, template, new HealthDecayRule(1, .03, Map.of("cannon", .25), true, true)));

		assertEquals(80, damage(stored, "components", "Hull"), EPSILON);
		assertEquals(97, damage(stored, "components", "pump"), EPSILON);
		assertEquals(100, damage(stored, "components", "balloon"), EPSILON);
		assertEquals(75, damage(stored, "weapons", "cannon"), EPSILON);
	}

	@Test
	void storedHullFloorReadsTheDecayedPumpWhenTheHullHasOne() {
		SinkableHull hull = new SinkableHull(config(400));
		Pump pump = new Pump(pumpConfig());
		hull.setPump(pump);
		Vehicle template = template(true, hull, pump);
		HealthDecayRule rule = new HealthDecayRule(1, .03, Map.of("pump", .9), true, false);

		JsonObject stored = stored("{components:{hull:{damage:0},PUMP:{damage:0}}}");
		assertTrue(VehicleHealthDecay.applyToJson(stored, template, rule));
		assertEquals(10, damage(stored, "components", "PUMP"), EPSILON);
		assertEquals(180, damage(stored, "components", "hull"), EPSILON);

		JsonObject noPump = stored("{components:{hull:{damage:0}}}");
		assertTrue(VehicleHealthDecay.applyToJson(noPump, template, rule));
		assertEquals(180, damage(noPump, "components", "hull"), EPSILON);
	}

	@Test
	void aWeakeningPumpNeverLeavesTheHullBelowItsThresholdOverManySteps() {
		SinkableHull hull = new SinkableHull(config(400));
		Pump pump = new Pump(pumpConfig());
		hull.setPump(pump);
		ActiveVehicle vehicle = live(true, List.of(pump, hull), null);
		HealthDecayRule rule = new HealthDecayRule(.2, .03, Map.of(), true, false);

		for (int step = 1; step <= 6; step++) {
			VehicleHealthDecay.applyToLive(vehicle, rule);
			assertTrue(hull.getHealthData().getHealthPercentage() >= hull.getSinkThreshold(),
					"step " + step + ": hull " + hull.getHealthData().getHealthPercentage()
							+ "% under threshold " + hull.getSinkThreshold());
		}
		assertEquals(3, pump.getHealthData().getHealthPercentage());
		assertEquals(80, hull.getHealthData().getHealthPercentage());

		JsonObject stored = stored("{components:{hull:{damage:0},pump:{damage:0}}}");
		VehicleHealthDecay.applyToJson(stored, template(true, hull, pump), rule);
		assertEquals(80, damage(stored, "components", "hull"), EPSILON);
	}

	@Test
	void aRuleThatDoesNotDecayReadsThePumpAsItIs() {
		SinkableHull hull = new SinkableHull(config(400));
		Pump pump = new Pump(pumpConfig());
		pump.getHealthData().setDamage(40);
		hull.setPump(pump);
		hull.getHealthData().setDamage(300);
		VehicleHealthDecay.applyToLive(live(true, List.of(hull, pump), null), new HealthDecayRule(0, .03, Map.of(), true, false));
		assertEquals(300, hull.getHealthData().getDamage(), EPSILON);
		assertEquals(40, pump.getHealthData().getDamage(), EPSILON);
	}

	@Test
	void storedSafeFloorsNeedAFloatingTemplateWithTheRightParts() {
		HealthDecayRule rule = new HealthDecayRule(1, .03, Map.of(), true, true);
		JsonObject sinking = stored("{components:{hull:{damage:0}}}");
		assertTrue(VehicleHealthDecay.applyToJson(sinking, template(false, new SinkableHull(config(400))), rule));
		assertEquals(388, damage(sinking, "components", "hull"), EPSILON);

		Vehicle noBehaviour = template(true, new SinkableHull(config(400)));
		when(noBehaviour.getBehaviourHandler()).thenReturn(null);
		JsonObject unknown = stored("{components:{hull:{damage:0}}}");
		assertTrue(VehicleHealthDecay.applyToJson(unknown, noBehaviour, rule));
		assertEquals(388, damage(unknown, "components", "hull"), EPSILON);

		JsonObject noHull = stored("{components:{pump:{damage:0}}}");
		assertTrue(VehicleHealthDecay.applyToJson(noHull, template(true, new SinkableHull(config(400)), new Pump(pumpConfig())), rule));
		assertEquals(97, damage(noHull, "components", "pump"), EPSILON);

		Vehicle bare = mock(Vehicle.class);
		JsonObject untouched = stored("{components:{hull:{damage:0}}}");
		assertFalse(VehicleHealthDecay.applyToJson(untouched, bare, rule));

		assertFalse(VehicleHealthDecay.applyToJson(null, bare, rule));
		assertFalse(VehicleHealthDecay.applyToJson(untouched, (Vehicle) null, rule));
		assertFalse(VehicleHealthDecay.applyToJson(untouched, bare, null));
	}

	private static ActiveVehicle live(boolean floats, List<VehicleComponent> components, ActiveWeapon weapon) {
		ActiveVehicle vehicle = mock(ActiveVehicle.class);
		when(vehicle.shouldFloat()).thenReturn(floats);
		when(vehicle.getComponents()).thenReturn(components);
		WeaponHandler weapons = mock(WeaponHandler.class);
		when(weapons.getWeapons()).thenReturn(weapon == null ? List.of() : List.of(weapon));
		when(vehicle.getWeaponHandler()).thenReturn(weapons);
		return vehicle;
	}

	private static Vehicle template(boolean floats, VehicleComponent... components) {
		Vehicle template = mock(Vehicle.class);
		ComponentHandler handler = new ComponentHandler(new YamlConfiguration());
		handler.getComponents().addAll(new ArrayList<>(List.of(components)));
		when(template.getComponentHandler()).thenReturn(handler);
		BehaviourHandler behaviour = mock(BehaviourHandler.class);
		when(behaviour.shouldFloat()).thenReturn(floats);
		when(template.getBehaviourHandler()).thenReturn(behaviour);
		Weapon cannon = mock(Weapon.class);
		when(cannon.getId()).thenReturn("cannon");
		when(cannon.getHealthData()).thenReturn(new HealthData(100, 0, 1));
		when(template.getWeapons()).thenReturn(List.of(cannon));
		return template;
	}

	private static YamlConfiguration config(double health) {
		YamlConfiguration config = new YamlConfiguration();
		config.set("health", health);
		config.set("damage-chance", 1);
		return config;
	}

	private static YamlConfiguration pumpConfig() {
		YamlConfiguration config = config(100);
		config.set("power", 50);
		return config;
	}

	private static JsonObject stored(String json) {
		return JsonParser.parseString(json).getAsJsonObject();
	}

	private static double damage(JsonObject root, String section, String part) {
		return root.getAsJsonObject(section).getAsJsonObject(part).get("damage").getAsDouble();
	}
}
